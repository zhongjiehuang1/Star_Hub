package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shared small-transaction orchestration. Parent intents retain quota until final publication/cleanup. */
final class AtomicPayloads {
    @FunctionalInterface interface Parent {void verify(Connection connection)throws Exception;}
    final AtomicDatabase database;private final byte[] secret,vault;
    AtomicPayloads(AtomicDatabase database,Properties config){
        this.database=database;secret=config.getProperty("storage.idempotency.secret","").getBytes(StandardCharsets.UTF_8);
        vault=Base64.getDecoder().decode(config.getProperty("vault.key",""));
        if(secret.length<32)throw new IllegalArgumentException("Payload identity is not configured");
    }
    byte[] mac(String purpose,byte[] value)throws Exception {Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(secret,"HmacSHA256"));hmac.update(purpose.getBytes(StandardCharsets.UTF_8));hmac.update((byte)0);return hmac.doFinal(value);}
    byte[] mac(String purpose,String value)throws Exception{return mac(purpose,value.getBytes(StandardCharsets.UTF_8));}
    ChunkedPayloadStore.Plan plan(String id,String operation,String kind,String owner,String request,byte[] bytes)throws Exception {
        return new ChunkedPayloadStore.Plan(id,operation,kind,mac("payload-owner-v1",owner),mac("payload-request-v1",request),bytes.length,ChunkedPayloadStore.chunks(bytes.length),FileStorage.hash(bytes));
    }
    static String id(String purpose,String value){return UUID.nameUUIDFromBytes((purpose+"\0"+value).getBytes(StandardCharsets.UTF_8)).toString();}
    byte[] encrypt(String id,String binding,byte[] plain)throws Exception {
        // The nonce binds the complete plaintext through a secret HMAC. A changed
        // value has a different nonce and cannot be accepted under the old plan.
        byte[] request=mac("payload-cipher-value-v1",plain);
        byte[] nonce=Arrays.copyOf(mac("payload-cipher-nonce-v1",id+"\0"+binding+"\0"+Base64.getEncoder().encodeToString(request)),12);
        byte[] encrypted=AccountServlet.crypt(true,plain,vault,nonce,"starhub-payload-v1:"+id+":"+binding);
        byte[] result=Arrays.copyOf(nonce,nonce.length+encrypted.length);System.arraycopy(encrypted,0,result,nonce.length,encrypted.length);return result;
    }
    byte[] decrypt(String id,String binding,byte[] cipher)throws Exception {
        if(cipher.length<28)throw new SQLException("Invalid payload ciphertext");
        return AccountServlet.crypt(false,Arrays.copyOfRange(cipher,12,cipher.length),vault,Arrays.copyOf(cipher,12),"starhub-payload-v1:"+id+":"+binding);
    }
    static ObjectNode json(ChunkedPayloadStore.Plan plan){
        return ModelGateway.JSON.createObjectNode().put("id",plan.id()).put("operation",plan.operation()).put("kind",plan.kind())
            .put("owner",Base64.getEncoder().encodeToString(plan.ownerHash())).put("request",Base64.getEncoder().encodeToString(plan.requestHash()))
            .put("bytes",plan.byteCount()).put("chunks",plan.chunkCount()).put("sha256",Base64.getEncoder().encodeToString(plan.sha256()));
    }
    static ChunkedPayloadStore.Plan parse(JsonNode json){
        try{return new ChunkedPayloadStore.Plan(json.path("id").asText(),json.path("operation").asText(),json.path("kind").asText(),
            Base64.getDecoder().decode(json.path("owner").asText()),Base64.getDecoder().decode(json.path("request").asText()),
            json.path("bytes").asLong(-1),json.path("chunks").asInt(-1),Base64.getDecoder().decode(json.path("sha256").asText()));}
        catch(Exception corrupt){throw StorageFailure.unavailable();}
    }
    void owner(ChunkedPayloadStore.Plan plan,String identity,String kind)throws Exception {
        if(!kind.equals(plan.kind())||!MessageDigest.isEqual(plan.ownerHash(),mac("payload-owner-v1",identity)))throw new SQLException("Payload owner binding changed");
    }
    void stage(ChunkedPayloadStore.Plan plan,byte[] bytes,Parent parent)throws Exception {
        stage(plan,bytes,parent,ignored->{});
    }
    void stage(ChunkedPayloadStore.Plan plan,byte[] bytes,Parent parent,Parent afterChunk)throws Exception {
        if(bytes.length!=plan.byteCount()||!MessageDigest.isEqual(FileStorage.hash(bytes),plan.sha256()))throw new SQLException("Payload plan changed");
        if(database.inside()){
            if(bytes.length>3*1024*1024)throw new SQLException("A large payload needs separately confirmed transactions");
            Connection c=database.current();parent.verify(c);ChunkedPayloadStore.reserve(c,plan);
            for(int index=0;index<plan.chunkCount();index++)ChunkedPayloadStore.putChunk(c,plan,index,Arrays.copyOfRange(bytes,index*ChunkedPayloadStore.CHUNK_BYTES,Math.min(bytes.length,(index+1)*ChunkedPayloadStore.CHUNK_BYTES)));
            afterChunk.verify(c);
            ChunkedPayloadStore.seal(c,plan);return;
        }
        database.run(plan.operation(),c->{parent.verify(c);ChunkedPayloadStore.reserve(c,plan);return null;});
        for(int index=0;index<plan.chunkCount();index++){
            final int part=index;byte[] chunk=Arrays.copyOfRange(bytes,index*ChunkedPayloadStore.CHUNK_BYTES,(int)Math.min(bytes.length,(long)(index+1)*ChunkedPayloadStore.CHUNK_BYTES));
            database.run(plan.operation(),c->{parent.verify(c);ChunkedPayloadStore.putChunk(c,plan,part,chunk);afterChunk.verify(c);return null;});
        }
        database.run(plan.operation(),c->{parent.verify(c);ChunkedPayloadStore.seal(c,plan);return null;});
    }
    void cleanup(ChunkedPayloadStore.Plan plan,Parent parent,boolean retired)throws Exception {
        cleanup(plan,parent,retired,ignored->{});
    }
    void cleanup(ChunkedPayloadStore.Plan plan,Parent parent,boolean retired,Parent afterChunk)throws Exception {
        if(database.inside())throw new SQLException("Payload cleanup requires separately confirmed transactions");
        boolean done=false;while(!done)done=database.run(plan.operation(),c->{
            parent.verify(c);if(retired)ChunkedPayloadStore.retire(c,plan);else ChunkedPayloadStore.cancel(c,plan);
            Integer next=ChunkedPayloadStore.nextChunkToDelete(c,plan);if(next!=null){ChunkedPayloadStore.deleteChunk(c,plan,next);afterChunk.verify(c);}
            if(ChunkedPayloadStore.nextChunkToDelete(c,plan)==null){ChunkedPayloadStore.finishCancel(c,plan);return true;}return false;
        });
    }

    static byte[] codeUnits(String value){byte[] bytes=new byte[value.length()*2];for(int i=0;i<value.length();i++){char unit=value.charAt(i);bytes[2*i]=(byte)(unit>>>8);bytes[2*i+1]=(byte)unit;}return bytes;}
    static String string(byte[] bytes)throws SQLException {if(bytes.length%2!=0)throw new SQLException("Invalid guest code units");char[] units=new char[bytes.length/2];for(int i=0;i<units.length;i++)units[i]=(char)(((bytes[2*i]&255)<<8)|(bytes[2*i+1]&255));return new String(units);}
}
