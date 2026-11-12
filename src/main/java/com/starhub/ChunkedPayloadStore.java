package com.starhub;

import java.io.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Immutable bounded payloads. Caller owns authority, identity, quota and commits.
 * Each row is at most 2 MiB; AtomicDatabase enforces a bounded aggregate chunk
 * mutation budget across every invocation. File/provider orchestration normally
 * writes one chunk per separately confirmed transaction.
 * No method commits, releases a budget, guesses liveness or performs timeout GC.
 */
final class ChunkedPayloadStore {
    static final int CHUNK_BYTES=2*1024*1024;
    static final long MAX_BYTES=2_000_000_000L;
    private ChunkedPayloadStore(){}

    /** ownerHash must bind immutable account generation or guest identity. */
    record Plan(String id,String operation,String kind,byte[] ownerHash,byte[] requestHash,long byteCount,int chunkCount,byte[] sha256){
        Plan{
            id=uuid(id);operation=uuid(operation);
            if(kind==null||!kind.matches("[A-Za-z0-9_./:-]{1,32}")||chunkCount!=chunks(byteCount))throw invalid();
            ownerHash=hash(ownerHash);requestHash=hash(requestHash);sha256=hash(sha256);
        }
        @Override public byte[] ownerHash(){return ownerHash.clone();}
        @Override public byte[] requestHash(){return requestHash.clone();}
        @Override public byte[] sha256(){return sha256.clone();}
        @Override public String toString(){return "Immutable payload plan";}
    }
    static int chunks(long bytes){if(bytes<0||bytes>MAX_BYTES)throw invalid();return (int)((bytes+CHUNK_BYTES-1)/CHUNK_BYTES);}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static byte[] hash(byte[] bytes){if(bytes==null||bytes.length!=32)throw invalid();return bytes.clone();}
    private static StorageFailure invalid(){return new StorageFailure(400,"INVALID_PAYLOAD","分块数据计划无效。");}
    private static StorageFailure conflict(){return new StorageFailure(409,"PAYLOAD_CONFLICT","分块数据与原操作不一致，请查询原操作结果。");}
    private static SQLException corrupt(){return new SQLException("Immutable payload integrity is unavailable");}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static void transaction(Connection c)throws SQLException{if(c.getAutoCommit())throw new SQLException("Payload changes require a caller-owned atomic transaction");}

    static void reserve(Connection c,Plan p)throws SQLException{
        transaction(c);String state=lookup(c,p,false);if(state!=null)return;
        try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_payload_manifests(payload_id,operation_id,kind,owner_hash,request_hash,byte_count,chunk_count,sha256,state) VALUES(?,?,?,?,?,?,?,?,'STAGING')")){
            q.setString(1,p.id);q.setString(2,p.operation);q.setString(3,p.kind);q.setBytes(4,p.ownerHash);q.setBytes(5,p.requestHash);q.setLong(6,p.byteCount);q.setInt(7,p.chunkCount);q.setBytes(8,p.sha256);q.executeUpdate();
        }
    }
    static String state(Connection c,Plan p)throws SQLException{return lookup(c,p,true);}
    private static String lookup(Connection c,Plan p,boolean required)throws SQLException{
        try(PreparedStatement q=c.prepareStatement("SELECT operation_id,kind,owner_hash,request_hash,byte_count,chunk_count,sha256,state FROM starhub_payload_manifests WHERE payload_id=?")){
            q.setString(1,p.id);try(ResultSet r=q.executeQuery()){
                if(!r.next()){if(required)throw conflict();return null;}
                if(!p.operation.equals(r.getString(1))||!p.kind.equals(r.getString(2))||!MessageDigest.isEqual(p.ownerHash,r.getBytes(3))
                        ||!MessageDigest.isEqual(p.requestHash,r.getBytes(4))||p.byteCount!=r.getLong(5)||p.chunkCount!=r.getInt(6)||!MessageDigest.isEqual(p.sha256,r.getBytes(7)))throw conflict();
                String state=r.getString(8);if(!Set.of("STAGING","READY","PUBLISHED","CANCELLING","CANCELLED").contains(state))throw corrupt();return state;
            }
        }
    }
    private static int length(Plan p,int index){
        if(index<0||index>=p.chunkCount)throw invalid();return (int)Math.min(CHUNK_BYTES,p.byteCount-(long)index*CHUNK_BYTES);
    }
    static void putChunk(Connection c,Plan p,int index,byte[] bytes)throws SQLException{
        transaction(c);int expected=length(p,index);if(bytes==null||bytes.length!=expected)throw invalid();
        String state=lookup(c,p,true);if(!state.equals("STAGING")&&!state.equals("READY")&&!state.equals("PUBLISHED"))throw conflict();
        byte[] digest=sha().digest(bytes);
        try(PreparedStatement q=c.prepareStatement("SELECT byte_count,sha256,content FROM starhub_payload_chunks WHERE payload_id=? AND chunk_index=?")){
            q.setString(1,p.id);q.setInt(2,index);try(ResultSet r=q.executeQuery()){
                if(r.next()){
                    byte[] old=r.getBytes(3);
                    if(old==null||r.getInt(1)!=expected||!MessageDigest.isEqual(digest,r.getBytes(2))||!MessageDigest.isEqual(bytes,old))throw conflict();return;
                }
            }
        }
        if(!state.equals("STAGING"))throw conflict();
        AtomicDatabase.claimChunkMutation(c,bytes.length);
        try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_payload_chunks(payload_id,chunk_index,byte_count,sha256,content) VALUES(?,?,?,?,?)")){
            q.setString(1,p.id);q.setInt(2,index);q.setInt(3,expected);q.setBytes(4,digest);q.setBytes(5,bytes);q.executeUpdate();
        }
    }
    /** Reads one row at a time, never an unbounded ResultSet or aggregate BLOB. */
    private static void copyVerified(Connection c,Plan p,OutputStream output)throws SQLException,IOException{
        MessageDigest complete=sha();long copied=0;
        for(int index=0;index<p.chunkCount;index++){
            try(PreparedStatement q=c.prepareStatement("SELECT byte_count,sha256,content FROM starhub_payload_chunks WHERE payload_id=? AND chunk_index=?")){
                q.setString(1,p.id);q.setInt(2,index);try(ResultSet r=q.executeQuery()){
                    if(!r.next())throw corrupt();byte[] bytes=r.getBytes(3);
                    if(bytes==null||bytes.length!=length(p,index)||r.getInt(1)!=bytes.length||!MessageDigest.isEqual(sha().digest(bytes),r.getBytes(2)))throw corrupt();
                    complete.update(bytes);copied+=bytes.length;if(output!=null)output.write(bytes);
                }
            }
        }
        try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM starhub_payload_chunks WHERE payload_id=?")){
            q.setString(1,p.id);try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong(1)!=p.chunkCount)throw corrupt();}
        }
        if(copied!=p.byteCount||!MessageDigest.isEqual(complete.digest(),p.sha256))throw corrupt();
    }
    static void seal(Connection c,Plan p)throws SQLException{
        transaction(c);String state=lookup(c,p,true);
        if(state.equals("READY")||state.equals("PUBLISHED"))return;
        if(!state.equals("STAGING"))throw conflict();
        try{copyVerified(c,p,null);}catch(IOException impossible){throw new SQLException("Payload verification failed");}
        transition(c,p,"STAGING","READY");
    }
    static void publish(Connection c,Plan p)throws SQLException{
        transaction(c);String state=lookup(c,p,true);if(state.equals("PUBLISHED"))return;
        if(!state.equals("READY"))throw conflict();transition(c,p,"READY","PUBLISHED");
    }
    /** Caller must authorize owner and parent state before reading READY data. */
    static void read(Connection c,Plan p,OutputStream output)throws SQLException,IOException{
        Objects.requireNonNull(output);String state=lookup(c,p,true);if(!state.equals("READY")&&!state.equals("PUBLISHED"))throw conflict();copyVerified(c,p,output);
    }
    static byte[] readBytes(Connection c,Plan p,int maxBytes)throws SQLException{
        if(maxBytes<0||p.byteCount>maxBytes||p.byteCount>64L*1024*1024)throw invalid();
        ByteArrayOutputStream out=new ByteArrayOutputStream((int)p.byteCount);
        try{read(c,p,out);}catch(IOException impossible){throw new SQLException("Payload read failed");}return out.toByteArray();
    }
    static void cancel(Connection c,Plan p)throws SQLException{
        transaction(c);String state=lookup(c,p,true);if(state.equals("CANCELLING")||state.equals("CANCELLED"))return;
        if(!state.equals("STAGING")&&!state.equals("READY"))throw conflict();transition(c,p,state,"CANCELLING");
    }
    /** Only after caller removed ALL live business references in this same TX. */
    static void retire(Connection c,Plan p)throws SQLException{
        transaction(c);String state=lookup(c,p,true);if(state.equals("CANCELLING")||state.equals("CANCELLED"))return;
        if(!state.equals("PUBLISHED"))throw conflict();transition(c,p,"PUBLISHED","CANCELLING");
    }
    static Integer nextChunkToDelete(Connection c,Plan p)throws SQLException{
        String state=lookup(c,p,true);if(!state.equals("CANCELLING")&&!state.equals("CANCELLED"))throw conflict();
        try(PreparedStatement q=c.prepareStatement("SELECT MIN(chunk_index) FROM starhub_payload_chunks WHERE payload_id=?")){
            q.setString(1,p.id);try(ResultSet r=q.executeQuery()){if(!r.next())throw corrupt();int value=r.getInt(1);return r.wasNull()?null:value;}
        }
    }
    static void deleteChunk(Connection c,Plan p,int index)throws SQLException{
        transaction(c);length(p,index);if(!lookup(c,p,true).equals("CANCELLING"))throw conflict();
        int bytes;
        try(PreparedStatement q=c.prepareStatement("SELECT byte_count FROM starhub_payload_chunks WHERE payload_id=? AND chunk_index=?")){
            q.setString(1,p.id);q.setInt(2,index);try(ResultSet r=q.executeQuery()){if(!r.next())return;bytes=r.getInt(1);if(bytes!=length(p,index))throw corrupt();}
        }
        AtomicDatabase.claimChunkMutation(c,bytes);
        try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_payload_chunks WHERE payload_id=? AND chunk_index=?")){q.setString(1,p.id);q.setInt(2,index);q.executeUpdate();}
    }
    /** Only this terminal state permits caller to release the retained budget. */
    static void finishCancel(Connection c,Plan p)throws SQLException{
        transaction(c);String state=lookup(c,p,true);if(state.equals("CANCELLED"))return;
        if(!state.equals("CANCELLING")||nextChunkToDelete(c,p)!=null)throw conflict();transition(c,p,"CANCELLING","CANCELLED");
    }
    private static void transition(Connection c,Plan p,String from,String to)throws SQLException{
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_payload_manifests SET state=? WHERE payload_id=? AND state=?")){
            q.setString(1,to);q.setString(2,p.id);q.setString(3,from);if(q.executeUpdate()!=1)throw conflict();
        }
    }
}
