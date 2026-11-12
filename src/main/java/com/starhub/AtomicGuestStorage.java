package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Certified encrypted guest state. Tomcat replicates only its immutable identity string. */
final class AtomicGuestStorage {
    static final int MAX_SESSIONS=1024,MAX_OPERATIONS=2048,MAX_TOTAL_OPERATIONS=65536;
    static final long IDLE_MILLIS=600_000;
    static final Set<String> FIELDS=Set.of("history","keys","pending");
    private static final SecureRandom RANDOM=new SecureRandom();
    record Reply(String identity,ObjectNode body,String durability){}
    @FunctionalInterface interface Capacity {void check(Connection c,long payloadBytes)throws Exception;}
    private final AtomicDatabase database;private final AtomicPayloads payloads;private final byte[] vault,identityKey;private final Capacity capacity;
    AtomicGuestStorage(AtomicDatabase database,Properties configuration,Capacity capacity){
        this.database=database;this.capacity=capacity;this.payloads=new AtomicPayloads(database,configuration);
        try{vault=Base64.getDecoder().decode(configuration.getProperty("vault.key",""));if(!Set.of(16,24,32).contains(vault.length))throw new IllegalArgumentException();}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Guest encryption vault is not configured");}
        identityKey=configuration.getProperty("storage.idempotency.secret","").getBytes(StandardCharsets.UTF_8);if(identityKey.length<32)throw new IllegalArgumentException("Guest request identity is not configured");
    }
    static String operationId(JsonNode input){return UUID.nameUUIDFromBytes((input.path("tab").asText()+":"+input.path("action").asText()+":"+input.path("tx").asText()+":"+input.path("index").asText("")).getBytes(StandardCharsets.US_ASCII)).toString();}
    private byte[] hmac(String purpose,String value)throws Exception {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(identityKey,"HmacSHA256"));return mac.doFinal(codeUnits(purpose+"\0"+value));}
    static String canonicalCookie(String value){
        if(value==null||value.isBlank()||value.length()>256)throw StorageFailure.unavailable();
        // Tomcat's jvmRoute is transport affinity, not a new logical session.
        int route=value.indexOf('.');return route<0?value:value.substring(0,route);
    }
    Reply handle(String cookie,String identity,JsonNode input,long now)throws Exception {
        String tab=input.path("tab").asText(),action=input.path("action").asText(),tx=input.path("tx").asText();
        if(!tab.matches("[0-9a-f]{32}")||!Set.of("get","status","begin","chunk","commit","snapshot","renew","identity").contains(action))throw invalid();
        boolean read=action.equals("get")||action.equals("status");if(!read&&!tx.matches("[0-9a-f]{32}"))throw invalid();
        String op=read?UUID.randomUUID().toString():operationId(input);byte[] cookieHash=hmac("guest-cookie",canonicalCookie(cookie)),requestHash=hmac("guest-request",AtomicHttp.canonical(input));
        if(!read&&!database.inside())drainCleanup(cookieHash,identity);
        return database.run(op,c->{
            String guest=resolve(c,cookieHash,identity,now,action.equals("renew"));ObjectNode response=ModelGateway.JSON.createObjectNode().put("epoch",guest).put("operationId",op).put("action",action).put("transactionId",tx);
            if(action.equals("status")){String target=FileStorage.operationId(input.path("operationId").asText());response.put("operationId",target).put("state","UNKNOWN");try(PreparedStatement q=c.prepareStatement("SELECT result_cipher FROM starhub_guest_operations WHERE guest_id=? AND operation_id=?")){q.setString(1,guest);q.setString(2,target);try(ResultSet r=q.executeQuery()){if(r.next())response=(ObjectNode)ModelGateway.JSON.readTree(decrypt(r.getBytes(1),guest+":"+target+":receipt"));}}return new Reply(guest,response,database.currentLease().mode());}
            if(read){
                for(String name:FIELDS){Value value=value(c,guest,tab,name);if(value!=null)response.set(name,ModelGateway.JSON.readTree(readValue(c,guest,tab,name,value)));}
                String after=input.path("after").asText();if(after.matches("[0-9a-f]{32}"))try(PreparedStatement q=c.prepareStatement("SELECT last_flush FROM starhub_guest_tabs WHERE guest_id=? AND tab_id=?")){q.setString(1,guest);q.setString(2,tab);try(ResultSet r=q.executeQuery()){if(!r.next()||!after.equals(r.getString(1)))response.put("pendingFlush",true);}}
                return new Reply(guest,response,database.currentLease().mode());
            }
            try(PreparedStatement q=c.prepareStatement("SELECT request_hash,result_cipher FROM starhub_guest_operations WHERE guest_id=? AND operation_id=?")){q.setString(1,guest);q.setString(2,op);try(ResultSet r=q.executeQuery()){if(r.next()){if(!MessageDigest.isEqual(requestHash,r.getBytes(1)))throw new StorageFailure(409,"GUEST_OPERATION_CONFLICT","访客操作编号已绑定其他内容，请保留本地数据并重新加载。");ObjectNode replay=(ObjectNode)ModelGateway.JSON.readTree(decrypt(r.getBytes(2),guest+":"+op+":receipt"));replay.put("transactionId",tx);return new Reply(guest,replay,database.currentLease().mode());}}}
            capacity.check(c,Math.max(262144,action.equals("snapshot")?2L*input.path("values").toString().length()+65536:262144));
            if(!action.equals("renew"))tab(c,guest,tab);long delta=0;
            switch(action){
                case "renew","identity"->{}
                case "begin"->{String name=field(input);long version=version(input);Value current=value(c,guest,tab,name);if(current!=null&&version<=current.version)throw stale();Stream old=stream(c,guest,tab);if(old!=null&&version<=old.version)throw stale();if(old!=null){queueCleanup(c,guest,tab,old.field,old.refs,old.units,old.legacy?"staging":null);removeStream(c,guest,tab,old.legacy);}writeStream(c,guest,tab,new Stream(name,version,tx,0,ModelGateway.JSON.createArrayNode(),0,false));}
                case "chunk"->{String name=field(input);Stream stage=matching(c,guest,tab,name,tx);JsonNode data=input.path("data"),index=input.path("index");if(!data.isTextual()||data.textValue().length()>100000||!index.isInt()||index.intValue()!=stage.next)throw invalid();String text=data.textValue();if(stage.units+text.length()>GuestSessionServlet.MAX_FIELD)throw tooLarge();
                    if(stage.legacy)throw new StorageFailure(503,"LEGACY_GUEST_MIGRATION_REQUIRED","旧访客上传需要受控迁移；原数据和预留保持不变。");
                    var refs=stage.refs.deepCopy();refs.add(put(c,guest,tab,name,stage.version,tx,op,index.intValue(),text,null));delta=text.length();writeStream(c,guest,tab,new Stream(name,stage.version,tx,stage.next+1,refs,stage.units+delta,false));}
                case "commit"->{String name=field(input);Stream stage=matching(c,guest,tab,name,tx);String text=readRefs(c,stage.refs,guest,tab,name,stage.version,stage.tx);if(!stage.legacy&&text.length()!=stage.units)throw StorageFailure.unavailable();if(stage.legacy)text=legacyStream(c,guest,tab,stage);try{if(ModelGateway.JSON.readTree(text)==null)throw invalid();}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw invalid();}Value old=value(c,guest,tab,name);if(old!=null&&stage.version<=old.version)throw stale();if(stage.legacy)throw new StorageFailure(503,"LEGACY_GUEST_MIGRATION_REQUIRED","旧访客上传需要受控迁移；原数据和预留保持不变。");
                    if(old!=null)queueCleanup(c,guest,tab,name,old.refs,old.units,old.legacy?"fields":null);writeValue(c,guest,tab,name,new Value(stage.version,tx,stage.refs,stage.units,false));removeStream(c,guest,tab,false);}
                case "snapshot"->{long version=version(input);JsonNode values=input.path("values");if(!values.isObject()||values.toString().length()>1_048_576)throw invalid();for(var names=values.fieldNames();names.hasNext();)if(!FIELDS.contains(names.next()))throw invalid();
                    var common=values.isEmpty()?null:put(c,guest,tab,"snapshot",version,tx,op,0,values.toString(),null);
                    for(String name:FIELDS)if(values.has(name)){String encoded=values.get(name).toString();if(encoded.length()>GuestSessionServlet.MAX_FIELD)throw tooLarge();Value old=value(c,guest,tab,name);if(old!=null&&version<=old.version)throw stale();if(old!=null)queueCleanup(c,guest,tab,name,old.refs,old.units,old.legacy?"fields":null);
                        ObjectNode selected=common.deepCopy();selected.put("selector",name);var refs=ModelGateway.JSON.createArrayNode().add(selected);writeValue(c,guest,tab,name,new Value(version,tx,refs,encoded.length(),false));delta=Math.addExact(delta,encoded.length());}
                    try(PreparedStatement q=c.prepareStatement("UPDATE starhub_guest_tabs SET last_flush=? WHERE guest_id=? AND tab_id=?")){q.setString(1,tx);q.setString(2,guest);q.setString(3,tab);q.executeUpdate();}}
                default->throw invalid();
            }
            capacity.check(c,0);budget(c,guest,delta);response.put("state","COMMITTED");byte[] receipt=encrypt(response.toString(),guest+":"+op+":receipt");if(receipt.length>16384)throw new SQLException("Guest receipt must contain only bounded status metadata");
            try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_guest_operations(guest_id,operation_id,request_hash,result_cipher) VALUES(?,?,?,?)")){q.setString(1,guest);q.setString(2,op);q.setBytes(3,requestHash);q.setBytes(4,receipt);q.executeUpdate();}
            return new Reply(guest,response,database.currentLease().mode());
        });
    }
    private String resolve(Connection c,byte[] cookie,String identity,long now,boolean renew)throws Exception {
        String guest=null;long expiry=0;
        String query=identity==null?"SELECT guest_id,expires_at_millis FROM starhub_guest_sessions WHERE cookie_hash=? FOR UPDATE":"SELECT guest_id,expires_at_millis FROM starhub_guest_sessions WHERE guest_id=? FOR UPDATE";
        try(PreparedStatement q=c.prepareStatement(query)){if(identity==null)q.setBytes(1,cookie);else q.setString(1,FileStorage.operationId(identity));try(ResultSet r=q.executeQuery()){if(r.next()){guest=r.getString(1);expiry=r.getLong(2);}}}
        if(guest==null){
            if(renew)throw new StorageFailure(404,"GUEST_SESSION_NOT_FOUND","访客会话不存在，请重新打开会话。");
            if(identity!=null)throw StorageFailure.unavailable();
            try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM starhub_guest_sessions")){r.next();if(r.getLong(1)>=MAX_SESSIONS)throw new StorageFailure(429,"GUEST_CAPACITY","访客会话容量已满，请保留当前页面的数据并稍后重试。");}
            capacity.check(c,65_536);guest=UUID.randomUUID().toString();
            try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_guest_sessions(guest_id,cookie_hash,expires_at_millis) VALUES(?,?,?)")){q.setString(1,guest);q.setBytes(2,cookie);q.setLong(3,Math.addExact(now,IDLE_MILLIS));q.executeUpdate();}
        }else if(now>=expiry&&!renew)throw new StorageFailure(410,"GUEST_SESSION_EXPIRED","访客会话已闲置超过十分钟，请保留当前页面的数据并重新打开会话。");
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_guest_sessions SET expires_at_millis=? WHERE guest_id=?")){q.setLong(1,Math.addExact(now,IDLE_MILLIS));q.setString(2,guest);q.executeUpdate();}return guest;
    }
    private void tab(Connection c,String guest,String tab)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT tab_id FROM starhub_guest_tabs WHERE guest_id=? AND tab_id=?")){q.setString(1,guest);q.setString(2,tab);try(ResultSet r=q.executeQuery()){if(r.next())return;}}
        try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM starhub_guest_tabs WHERE guest_id=?")){q.setString(1,guest);try(ResultSet r=q.executeQuery()){r.next();if(r.getInt(1)>=GuestSessionServlet.MAX_TABS)throw new StorageFailure(429,"GUEST_TAB_LIMIT","访客标签页数量已达到上限。");}}
        try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_guest_tabs(guest_id,tab_id) VALUES(?,?)")){q.setString(1,guest);q.setString(2,tab);q.executeUpdate();}
    }
    private void budget(Connection c,String guest,long delta)throws SQLException {
        long sessionUsed,sessionOps,totalUsed,totalOps;
        try(PreparedStatement q=c.prepareStatement("SELECT used_units,operation_count FROM starhub_guest_sessions WHERE guest_id=? FOR UPDATE")){q.setString(1,guest);try(ResultSet r=q.executeQuery()){if(!r.next())throw StorageFailure.unavailable();sessionUsed=r.getLong(1);sessionOps=r.getLong(2);}}
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT used_units,operation_count FROM starhub_guest_budget WHERE id=1 FOR UPDATE")){if(!r.next())throw StorageFailure.unavailable();totalUsed=r.getLong(1);totalOps=r.getLong(2);}
        if(delta< -sessionUsed||delta< -totalUsed||delta>GuestSessionServlet.MAX_SESSION-sessionUsed||delta>GuestSessionServlet.MAX_TOTAL-totalUsed)throw tooLarge();
        if(sessionOps>=MAX_OPERATIONS||totalOps>=MAX_TOTAL_OPERATIONS)throw new StorageFailure(429,"GUEST_OPERATION_LIMIT","访客会话操作容量已满，请保留当前页面的数据。");
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_guest_sessions SET used_units=used_units+?,operation_count=operation_count+1 WHERE guest_id=?")){q.setLong(1,delta);q.setString(2,guest);q.executeUpdate();}
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_guest_budget SET used_units=used_units+?,operation_count=operation_count+1 WHERE id=1")){q.setLong(1,delta);q.executeUpdate();}
    }
    private record Value(long version,String tx,com.fasterxml.jackson.databind.node.ArrayNode refs,long units,boolean legacy){}
    private record Stream(String field,long version,String tx,int next,com.fasterxml.jackson.databind.node.ArrayNode refs,long units,boolean legacy){}
    private Value value(Connection c,String guest,String tab,String name)throws Exception {
        try(PreparedStatement q=c.prepareStatement("SELECT version,transaction_id,refs_json,size_units FROM starhub_guest_values WHERE guest_id=? AND tab_id=? AND field_name=?")){q.setString(1,guest);q.setString(2,tab);q.setString(3,name);try(ResultSet r=q.executeQuery()){if(r.next())return new Value(r.getLong(1),r.getString(2),(com.fasterxml.jackson.databind.node.ArrayNode)ModelGateway.JSON.readTree(r.getString(3)),r.getLong(4),false);}}
        try(PreparedStatement q=c.prepareStatement("SELECT version,transaction_id,size_units FROM starhub_guest_fields f WHERE guest_id=? AND tab_id=? AND field_name=? AND NOT EXISTS(SELECT 1 FROM starhub_guest_cleanup x WHERE x.guest_id=f.guest_id AND x.legacy_tab=f.tab_id AND x.legacy_field=f.field_name AND x.legacy_kind='fields')")){q.setString(1,guest);q.setString(2,tab);q.setString(3,name);try(ResultSet r=q.executeQuery()){return r.next()?new Value(r.getLong(1),r.getString(2),ModelGateway.JSON.createArrayNode(),r.getLong(3),true):null;}}
    }
    private Stream stream(Connection c,String guest,String tab)throws Exception {
        try(PreparedStatement q=c.prepareStatement("SELECT field_name,version,transaction_id,next_chunk,refs_json,size_units FROM starhub_guest_streams WHERE guest_id=? AND tab_id=?")){q.setString(1,guest);q.setString(2,tab);try(ResultSet r=q.executeQuery()){if(r.next())return new Stream(r.getString(1),r.getLong(2),r.getString(3),r.getInt(4),(com.fasterxml.jackson.databind.node.ArrayNode)ModelGateway.JSON.readTree(r.getString(5)),r.getLong(6),false);}}
        try(PreparedStatement q=c.prepareStatement("SELECT field_name,version,transaction_id,next_chunk,size_units FROM starhub_guest_staging f WHERE guest_id=? AND tab_id=? AND NOT EXISTS(SELECT 1 FROM starhub_guest_cleanup x WHERE x.guest_id=f.guest_id AND x.legacy_tab=f.tab_id AND x.legacy_kind='staging')")){q.setString(1,guest);q.setString(2,tab);try(ResultSet r=q.executeQuery()){return r.next()?new Stream(r.getString(1),r.getLong(2),r.getString(3),r.getInt(4),ModelGateway.JSON.createArrayNode(),r.getLong(5),true):null;}}
    }
    private Stream matching(Connection c,String guest,String tab,String name,String tx)throws Exception {Stream stream=stream(c,guest,tab);if(stream==null||!stream.field.equals(name)||!stream.tx.equals(tx))throw new StorageFailure(409,"GUEST_STREAM_CHANGED","访客数据上传状态已更改，请保留本地数据并重试。");return stream;}
    private ObjectNode put(Connection c,String guest,String tab,String name,long version,String tx,String op,int index,String text,String selector)throws Exception {
        String binding=guest+":"+tab+":"+name+":"+version+":"+tx+":"+index,id=AtomicPayloads.id("guest-payload",op);byte[] cipher=payloads.encrypt(id,binding,AtomicPayloads.codeUnits(text));var plan=payloads.plan(id,op,"GUEST",guest+":"+tab,binding,cipher);payloads.stage(plan,cipher,ignored->{});ChunkedPayloadStore.publish(c,plan);ObjectNode ref=ModelGateway.JSON.createObjectNode().put("binding",binding);ref.set("plan",AtomicPayloads.json(plan));return ref;
    }
    private String readRefs(Connection c,JsonNode refs,String guest,String tab,String field,long version,String tx)throws Exception {
        StringBuilder result=new StringBuilder();int part=0;for(JsonNode ref:refs){var plan=AtomicPayloads.parse(ref.path("plan"));payloads.owner(plan,guest+":"+tab,"GUEST");String expected=guest+":"+tab+":"+(ref.has("selector")?"snapshot":field)+":"+version+":"+tx+":";String expectedOperation=UUID.nameUUIDFromBytes((tab+":"+(ref.has("selector")?"snapshot":"chunk")+":"+tx+":"+(ref.has("selector")?"":Integer.toString(part))).getBytes(StandardCharsets.US_ASCII)).toString();if(!ref.path("binding").asText().equals(expected+part++)||!plan.operation().equals(expectedOperation)||ref.has("selector")&&!field.equals(ref.path("selector").asText())||!plan.id().equals(AtomicPayloads.id("guest-payload",plan.operation())))throw StorageFailure.unavailable();byte[] cipher=ChunkedPayloadStore.readBytes(c,plan,2*GuestSessionServlet.MAX_FIELD+4096);String value=AtomicPayloads.string(payloads.decrypt(plan.id(),ref.path("binding").asText(),cipher));if(ref.has("selector"))value=ModelGateway.JSON.readTree(value).path(ref.path("selector").asText()).toString();result.append(value);if(result.length()>GuestSessionServlet.MAX_FIELD)throw StorageFailure.unavailable();}return result.toString();
    }
    private String readValue(Connection c,String guest,String tab,String name,Value value)throws Exception {
        if(!value.legacy){String result=readRefs(c,value.refs,guest,tab,name,value.version,value.tx);if(result.length()!=value.units)throw StorageFailure.unavailable();return result;}try(PreparedStatement q=c.prepareStatement("SELECT value_cipher FROM starhub_guest_fields WHERE guest_id=? AND tab_id=? AND field_name=?")){q.setString(1,guest);q.setString(2,tab);q.setString(3,name);try(ResultSet r=q.executeQuery()){if(!r.next())throw StorageFailure.unavailable();return decrypt(r.getBytes(1),aad(guest,tab,name,value.version,value.tx,"value"));}}
    }
    private String legacyStream(Connection c,String guest,String tab,Stream value)throws Exception {try(PreparedStatement q=c.prepareStatement("SELECT value_cipher FROM starhub_guest_staging WHERE guest_id=? AND tab_id=?")){q.setString(1,guest);q.setString(2,tab);try(ResultSet r=q.executeQuery()){if(!r.next())throw StorageFailure.unavailable();return decrypt(r.getBytes(1),aad(guest,tab,value.field,value.version,value.tx,"staging"));}}}
    private void writeValue(Connection c,String guest,String tab,String name,Value value)throws Exception {
        Value previous=value(c,guest,tab,name);if(previous!=null&&!previous.legacy)references(c,guest,previous.refs,-1,0);references(c,guest,value.refs,1,0);
        try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_guest_values WHERE guest_id=? AND tab_id=? AND field_name=?")){q.setString(1,guest);q.setString(2,tab);q.setString(3,name);q.executeUpdate();}
        try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_guest_values(guest_id,tab_id,field_name,version,transaction_id,refs_json,size_units) VALUES(?,?,?,?,?,?,?)")){q.setString(1,guest);q.setString(2,tab);q.setString(3,name);q.setLong(4,value.version);q.setString(5,value.tx);q.setString(6,value.refs.toString());q.setLong(7,value.units);q.executeUpdate();}
    }
    private void writeStream(Connection c,String guest,String tab,Stream value)throws Exception {
        removeStream(c,guest,tab,false);references(c,guest,value.refs,1,0,1);try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_guest_streams(guest_id,tab_id,field_name,version,transaction_id,next_chunk,refs_json,size_units) VALUES(?,?,?,?,?,?,?,?)")){q.setString(1,guest);q.setString(2,tab);q.setString(3,value.field);q.setLong(4,value.version);q.setString(5,value.tx);q.setInt(6,value.next);q.setString(7,value.refs.toString());q.setLong(8,value.units);q.executeUpdate();}
    }
    private void removeStream(Connection c,String guest,String tab,boolean legacy)throws Exception {if(legacy)return;try(PreparedStatement prior=c.prepareStatement("SELECT refs_json FROM starhub_guest_streams WHERE guest_id=? AND tab_id=?")){prior.setString(1,guest);prior.setString(2,tab);try(ResultSet r=prior.executeQuery()){if(r.next())references(c,guest,ModelGateway.JSON.readTree(r.getString(1)),-1,0,-1);}}try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_guest_streams WHERE guest_id=? AND tab_id=?")){q.setString(1,guest);q.setString(2,tab);q.executeUpdate();}}
    private void queueCleanup(Connection c,String guest,String tab,String name,JsonNode refs,long units,String legacy)throws Exception {
        if(units==0&&refs.isEmpty())return;references(c,guest,refs,0,1);try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_guest_cleanup(cleanup_id,guest_id,refs_json,size_units,state,legacy_kind,legacy_tab,legacy_field) VALUES(?,?,?,?,'CANCELLING',?,?,?)")){q.setString(1,UUID.randomUUID().toString());q.setString(2,guest);q.setString(3,refs.toString());q.setLong(4,units);q.setString(5,legacy);q.setString(6,tab);q.setString(7,name);q.executeUpdate();}
    }
    private record Cleanup(String id,String guest,JsonNode refs,long units,String legacy,String tab,String field){}
    private void references(Connection c,String guest,JsonNode refs,long live,long cleanup)throws Exception {references(c,guest,refs,live,cleanup,0);}
    private void references(Connection c,String guest,JsonNode refs,long live,long cleanup,long staging)throws Exception {
        Set<String> seen=new HashSet<>();for(JsonNode ref:refs){var plan=AtomicPayloads.parse(ref.path("plan"));payloads.owner(plan,guest+":"+ref.path("binding").asText().split(":",3)[1],"GUEST");if(!seen.add(plan.id()))continue;
            try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO starhub_guest_payload_refs(payload_id,guest_id,live_refs,cleanup_refs) VALUES(?,?,0,0)")){q.setString(1,plan.id());q.setString(2,guest);q.executeUpdate();}
            try(PreparedStatement q=c.prepareStatement("UPDATE starhub_guest_payload_refs SET live_refs=live_refs+?,cleanup_refs=cleanup_refs+?,staging_refs=staging_refs+? WHERE payload_id=? AND guest_id=? AND live_refs>=? AND cleanup_refs>=? AND staging_refs>=?")){q.setLong(1,live);q.setLong(2,cleanup);q.setLong(3,staging);q.setString(4,plan.id());q.setString(5,guest);q.setLong(6,Math.max(0,-live));q.setLong(7,Math.max(0,-cleanup));q.setLong(8,Math.max(0,-staging));if(q.executeUpdate()!=1)throw new SQLException("Guest payload reference count changed");}
        }
    }
    private boolean referenced(Connection c,String guest,String id)throws SQLException {try(PreparedStatement q=c.prepareStatement("SELECT live_refs FROM starhub_guest_payload_refs WHERE payload_id=? AND guest_id=?")){q.setString(1,id);q.setString(2,guest);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Guest payload reference state missing");if(r.getLong(1)>0)return true;}}for(String table:List.of("starhub_guest_values","starhub_guest_streams"))try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE guest_id=? AND refs_json LIKE ?")){q.setString(1,guest);q.setString(2,"%\"id\":\""+id+"\"%");try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong(1)>0)throw new SQLException("Guest live-reference proof changed");}}return false;}
    private void drainCleanup(byte[] cookie,String identity)throws Exception {
        List<Cleanup> pending=database.readHint(c->{String guest=identity;if(guest==null)try(PreparedStatement q=c.prepareStatement("SELECT guest_id FROM starhub_guest_sessions WHERE cookie_hash=?")){q.setBytes(1,cookie);try(ResultSet r=q.executeQuery()){if(r.next())guest=r.getString(1);}}List<Cleanup> result=new ArrayList<>();if(guest==null)return result;try(PreparedStatement q=c.prepareStatement("SELECT cleanup_id,refs_json,size_units,legacy_kind,legacy_tab,legacy_field FROM starhub_guest_cleanup WHERE guest_id=? AND state='CANCELLING'")){q.setString(1,guest);try(ResultSet r=q.executeQuery()){while(r.next())result.add(new Cleanup(r.getString(1),guest,ModelGateway.JSON.readTree(r.getString(2)),r.getLong(3),r.getString(4),r.getString(5),r.getString(6)));}}return result;});
        for(Cleanup cleanup:pending){
            boolean blocked=false;for(JsonNode ref:cleanup.refs){var plan=AtomicPayloads.parse(ref.path("plan"));payloads.owner(plan,cleanup.guest+":"+cleanup.tab,"GUEST");if(database.run(cleanup.id,c->referenced(c,cleanup.guest,plan.id()))){blocked=true;continue;}payloads.cleanup(plan,c->{if(referenced(c,cleanup.guest,plan.id()))throw StorageFailure.pending();},true);}if(blocked)continue;
            database.run(cleanup.id,c->{try(PreparedStatement q=c.prepareStatement("SELECT state FROM starhub_guest_cleanup WHERE cleanup_id=?")){q.setString(1,cleanup.id);try(ResultSet r=q.executeQuery()){if(!r.next()||!"CANCELLING".equals(r.getString(1)))return null;}}
                references(c,cleanup.guest,cleanup.refs,0,-1);if(cleanup.legacy!=null){String table=cleanup.legacy.equals("fields")?"starhub_guest_fields":"starhub_guest_staging";String where="guest_id=? AND tab_id=?"+(cleanup.legacy.equals("fields")?" AND field_name=?":"");int size=0;try(PreparedStatement q=c.prepareStatement("SELECT OCTET_LENGTH(value_cipher) FROM "+table+" WHERE "+where)){q.setString(1,cleanup.guest);q.setString(2,cleanup.tab);if(cleanup.legacy.equals("fields"))q.setString(3,cleanup.field);try(ResultSet r=q.executeQuery()){if(r.next())size=r.getInt(1);}}if(size>ChunkedPayloadStore.CHUNK_BYTES)throw new StorageFailure(503,"LEGACY_GUEST_MIGRATION_REQUIRED","旧访客数据需要受控迁移；预留不释放。");if(size>0){AtomicDatabase.claimChunkMutation(c,size);try(PreparedStatement q=c.prepareStatement("DELETE FROM "+table+" WHERE "+where)){q.setString(1,cleanup.guest);q.setString(2,cleanup.tab);if(cleanup.legacy.equals("fields"))q.setString(3,cleanup.field);q.executeUpdate();}}}
                try(PreparedStatement q=c.prepareStatement("UPDATE starhub_guest_sessions SET used_units=used_units-? WHERE guest_id=? AND used_units>=?")){q.setLong(1,cleanup.units);q.setString(2,cleanup.guest);q.setLong(3,cleanup.units);if(q.executeUpdate()!=1)throw new SQLException("Guest cleanup budget changed");}try(PreparedStatement q=c.prepareStatement("UPDATE starhub_guest_budget SET used_units=used_units-? WHERE id=1 AND used_units>=?")){q.setLong(1,cleanup.units);q.setLong(2,cleanup.units);if(q.executeUpdate()!=1)throw new SQLException("Guest global cleanup budget changed");}try(PreparedStatement q=c.prepareStatement("UPDATE starhub_guest_cleanup SET state='CANCELLED' WHERE cleanup_id=? AND state='CANCELLING'")){q.setString(1,cleanup.id);if(q.executeUpdate()!=1)throw new SQLException("Guest cleanup terminal changed");}return null;
            });
        }
    }
    private static byte[] codeUnits(String value){byte[] data=new byte[value.length()*2];for(int i=0;i<value.length();i++){char unit=value.charAt(i);data[2*i]=(byte)(unit>>>8);data[2*i+1]=(byte)unit;}return data;}
    private byte[] encrypt(String value,String aad)throws Exception {byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);byte[] encrypted=AccountServlet.crypt(true,codeUnits(value),vault,nonce,aad);byte[] result=Arrays.copyOf(nonce,nonce.length+encrypted.length);System.arraycopy(encrypted,0,result,nonce.length,encrypted.length);return result;}
    private String decrypt(byte[] value,String aad)throws Exception {if(value==null||value.length<28)throw StorageFailure.unavailable();byte[] plain=AccountServlet.crypt(false,Arrays.copyOfRange(value,12,value.length),vault,Arrays.copyOf(value,12),aad);if(plain.length%2!=0)throw StorageFailure.unavailable();char[] units=new char[plain.length/2];for(int i=0;i<units.length;i++)units[i]=(char)(((plain[2*i]&255)<<8)|(plain[2*i+1]&255));return new String(units);}
    private static String aad(String guest,String tab,String field,long version,String tx,String purpose){return "starhub-guest-v1:"+guest+":"+tab+":"+field+":"+version+":"+tx+":"+purpose;}
    private static String field(JsonNode input){String field=input.path("field").asText();if(!FIELDS.contains(field))throw invalid();return field;}
    private static long version(JsonNode input){JsonNode version=input.path("version");if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<1)throw invalid();return version.longValue();}
    private static StorageFailure invalid(){return new StorageFailure(400,"INVALID_GUEST_REQUEST","访客数据请求无效。");}
    private static StorageFailure stale(){return new StorageFailure(409,"GUEST_VERSION_CONFLICT","访客数据已在另一请求中更改，请保留本地数据并重新加载。");}
    private static StorageFailure tooLarge(){return new StorageFailure(413,"GUEST_STORAGE_LIMIT","访客数据超过当前会话或共享容量限制，请保留本地数据。");}
}
