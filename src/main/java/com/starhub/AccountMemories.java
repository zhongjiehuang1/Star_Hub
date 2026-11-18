package com.starhub;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.*;
import java.text.Normalizer;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Account-scoped encrypted memory. All mutations require the caller's atomic transaction. */
final class AccountMemories {
    static final int MAX_ITEMS=50,MAX_TEXT=120,MAX_BATCH=10;
    private static final Set<String> KINDS=Set.of("NAME","RESPONSE_LANGUAGE","RESPONSE_STYLE","PREFERENCE","FACT");
    private static final Set<String> SINGLETONS=Set.of("NAME","RESPONSE_LANGUAGE","RESPONSE_STYLE");
    private static final SecureRandom RANDOM=new SecureRandom();
    private record Settings(boolean enabled,long revision) {}
    private AccountMemories() {}

    private static StorageFailure conflict(){return new StorageFailure(409,"MEMORY_CHANGED","记忆已在其他页面更改，请重新读取。 ");}
    private static StorageFailure invalid(){return new StorageFailure(400,"INVALID_MEMORY","记忆内容或操作无效。 ");}
    private static void checkKey(byte[] key){if(key==null||!Set.of(16,24,32).contains(key.length))throw new IllegalArgumentException("Memory vault key unavailable");}
    private static void validateOwner(long user,String generation){if(user<=0||generation==null||!generation.matches("[0-9a-f-]{36}"))throw invalid();}
    private static void lockOwner(Connection c,long user,String generation)throws SQLException{
        validateOwner(user,generation);if(c.getAutoCommit())throw new SQLException("Memory mutation requires a caller-owned transaction");
        try(PreparedStatement q=c.prepareStatement("SELECT generation FROM users WHERE id=? AND active=1 FOR UPDATE")){
            q.setLong(1,user);try(ResultSet r=q.executeQuery()){if(!r.next()||!generation.equals(r.getString(1)))throw StorageFailure.unavailable();}
        }
    }
    private static Settings settings(Connection c,long user,String generation)throws SQLException{
        validateOwner(user,generation);
        try(PreparedStatement q=c.prepareStatement("SELECT enabled,revision FROM starhub_memory_settings WHERE user_id=? AND owner_generation=?")){
            q.setLong(1,user);q.setString(2,generation);try(ResultSet r=q.executeQuery()){return r.next()?new Settings(r.getBoolean(1),r.getLong(2)):new Settings(false,0);}
        }
    }
    private static void expect(Settings settings,long revision){if(revision<0||settings.revision()!=revision)throw conflict();}
    static void requireEnabledRevision(Connection c,long user,String generation,long revision)throws SQLException{
        lockOwner(c,user,generation);Settings current=settings(c,user,generation);expect(current,revision);
        if(!current.enabled())throw new StorageFailure(409,"MEMORY_DISABLED","记忆设置已更改，请重新发送。");
    }
    private static long advance(Connection c,long user,String generation,Settings before,boolean enabled)throws SQLException{
        long next=Math.addExact(before.revision(),1);
        if(before.revision()==0){
            try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_memory_settings(user_id,owner_generation,enabled,revision) VALUES(?,?,?,?)")){
                q.setLong(1,user);q.setString(2,generation);q.setBoolean(3,enabled);q.setLong(4,next);q.executeUpdate();
            }
        }else{
            try(PreparedStatement q=c.prepareStatement("UPDATE starhub_memory_settings SET enabled=?,revision=? WHERE user_id=? AND owner_generation=? AND revision=?")){
                q.setBoolean(1,enabled);q.setLong(2,next);q.setLong(3,user);q.setString(4,generation);q.setLong(5,before.revision());if(q.executeUpdate()!=1)throw conflict();
            }
        }return next;
    }
    private static String aad(long user,String generation,String id,String kind){return "starhub-account-memory-v1:"+user+":"+generation+":"+id+":"+kind;}
    private static byte[] fingerprint(byte[] key,long user,String generation,String kind,String text)throws GeneralSecurityException{
        checkKey(key);Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        String value=Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return mac.doFinal(("starhub-memory-dedup-v1\0"+user+"\0"+generation+"\0"+kind+"\0"+value).getBytes(StandardCharsets.UTF_8));
    }
    private static String validateText(String value){
        if(value==null)throw invalid();String text=Normalizer.normalize(value,Normalizer.Form.NFKC).strip();
        if(text.isEmpty()||text.length()>MAX_TEXT||text.getBytes(StandardCharsets.UTF_8).length>1024||text.codePoints().anyMatch(ch->Character.isISOControl(ch)||Character.getType(ch)==Character.FORMAT||Character.getType(ch)==Character.SURROGATE))throw invalid();
        return text;
    }
    private static String kind(String value){if(value==null||!KINDS.contains(value))throw invalid();return value;}
    private static String id(String value){if(value==null)throw invalid();try{if(!UUID.fromString(value).toString().equals(value))throw invalid();return value;}catch(IllegalArgumentException bad){throw invalid();}}

    static ObjectNode state(Connection c,long user,String generation,byte[] key)throws SQLException,GeneralSecurityException{
        checkKey(key);lockOwner(c,user,generation);Settings setting=settings(c,user,generation);ObjectNode out=ModelGateway.JSON.createObjectNode().put("enabled",setting.enabled()).put("revision",setting.revision());ArrayNode items=out.putArray("memories");
        try(PreparedStatement q=c.prepareStatement("SELECT id,kind,nonce,ciphertext FROM starhub_account_memories WHERE user_id=? AND owner_generation=? ORDER BY updated_at,created_at,id LIMIT 51")){
            q.setLong(1,user);q.setString(2,generation);try(ResultSet r=q.executeQuery()){while(r.next()){
                if(items.size()>=MAX_ITEMS)throw new SQLException("Memory count exceeds bound");String id=id(r.getString(1)),kind=kind(r.getString(2));byte[] nonce=r.getBytes(3),cipher=r.getBytes(4);
                if(nonce==null||nonce.length!=12||cipher==null||cipher.length>2048)throw new SQLException("Invalid memory ciphertext");
                String text=validateText(new String(AccountServlet.crypt(false,cipher,key,nonce,aad(user,generation,id,kind)),StandardCharsets.UTF_8));
                items.addObject().put("id",id).put("kind",kind).put("text",text);
            }}
        }return out;
    }
    // Disabling memory retains records for the settings UI but supplies none to
    // a future model request. A caller must freeze this result per operation.
    static ObjectNode promptSnapshot(Connection c,long user,String generation,byte[] key)throws SQLException,GeneralSecurityException{
        lockOwner(c,user,generation);Settings setting=settings(c,user,generation);if(!setting.enabled())return ModelGateway.JSON.createObjectNode().put("enabled",false).put("revision",setting.revision()).set("memories",ModelGateway.JSON.createArrayNode());
        return state(c,user,generation,key);
    }
    static ObjectNode setEnabled(Connection c,long user,String generation,long expected,boolean enabled)throws SQLException{
        lockOwner(c,user,generation);Settings old=settings(c,user,generation);expect(old,expected);
        long revision=old.enabled()==enabled?old.revision():advance(c,user,generation,old,enabled);
        return ModelGateway.JSON.createObjectNode().put("enabled",enabled).put("revision",revision);
    }
    static ObjectNode save(Connection c,long user,String generation,long expected,List<MemoryExtractor.Candidate> candidates,byte[] key)throws SQLException,GeneralSecurityException{
        if(candidates==null||candidates.size()>MAX_BATCH)throw invalid();for(MemoryExtractor.Candidate entry:candidates)if(!MemoryExtractor.allowed(entry))throw invalid();
        return saveValidated(c,user,generation,expected,candidates,key);
    }
    // Caller must bind providerReply to the original authenticated paid
    // operation and confirm saved user-message provenance before invoking it.
    static ObjectNode saveSemantic(Connection c,long user,String generation,long expected,String userInput,String providerReply,byte[] key)throws SQLException,GeneralSecurityException,java.io.IOException{
        return saveValidated(c,user,generation,expected,SemanticMemoryExtractor.validate(userInput,providerReply),key);
    }
    private static ObjectNode saveValidated(Connection c,long user,String generation,long expected,List<MemoryExtractor.Candidate> candidates,byte[] key)throws SQLException,GeneralSecurityException{
        checkKey(key);if(candidates==null||candidates.size()>MAX_BATCH)throw invalid();Set<String> seen=new HashSet<>();for(MemoryExtractor.Candidate entry:candidates)if(entry==null||!seen.add(entry.kind()))throw invalid();
        lockOwner(c,user,generation);Settings old=settings(c,user,generation);expect(old,expected);if(!old.enabled())throw new StorageFailure(409,"MEMORY_DISABLED","记忆功能未开启。 ");
        int count;try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM starhub_account_memories WHERE user_id=? AND owner_generation=?")){q.setLong(1,user);q.setString(2,generation);try(ResultSet r=q.executeQuery()){r.next();count=r.getInt(1);}}
        int saved=0;
        for(MemoryExtractor.Candidate entry:candidates){String type=kind(entry.kind()),text=validateText(entry.text());byte[] hash=fingerprint(key,user,generation,type,text);String existing=null;byte[] current=null;
            String sql=SINGLETONS.contains(type)?"SELECT id,fingerprint FROM starhub_account_memories WHERE user_id=? AND owner_generation=? AND kind=?":"SELECT id,fingerprint FROM starhub_account_memories WHERE user_id=? AND owner_generation=? AND fingerprint=?";
            try(PreparedStatement q=c.prepareStatement(sql)){q.setLong(1,user);q.setString(2,generation);if(SINGLETONS.contains(type))q.setString(3,type);else q.setBytes(3,hash);try(ResultSet r=q.executeQuery()){if(r.next()){existing=id(r.getString(1));current=r.getBytes(2);if(r.next())throw new SQLException("Ambiguous memory record");}}}
            if(existing!=null&&java.security.MessageDigest.isEqual(current,hash))continue;
            if(existing==null&&count>=MAX_ITEMS)throw new StorageFailure(413,"MEMORY_LIMIT","记忆数量已达上限，请先删除不需要的内容。 ");
            String record=existing==null?UUID.randomUUID().toString():existing;byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);byte[] cipher=AccountServlet.crypt(true,text.getBytes(StandardCharsets.UTF_8),key,nonce,aad(user,generation,record,type));
            if(existing==null){try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_account_memories(id,user_id,owner_generation,kind,fingerprint,nonce,ciphertext) VALUES(?,?,?,?,?,?,?)")){q.setString(1,record);q.setLong(2,user);q.setString(3,generation);q.setString(4,type);q.setBytes(5,hash);q.setBytes(6,nonce);q.setBytes(7,cipher);q.executeUpdate();}count++;}
            else try(PreparedStatement q=c.prepareStatement("UPDATE starhub_account_memories SET fingerprint=?,nonce=?,ciphertext=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND owner_generation=?")){q.setBytes(1,hash);q.setBytes(2,nonce);q.setBytes(3,cipher);q.setString(4,record);q.setLong(5,user);q.setString(6,generation);if(q.executeUpdate()!=1)throw conflict();}
            saved++;
        }
        long revision=saved==0?old.revision():advance(c,user,generation,old,true);
        return ModelGateway.JSON.createObjectNode().put("saved",saved).put("revision",revision);
    }
    static ObjectNode delete(Connection c,long user,String generation,long expected,String memoryId)throws SQLException{
        id(memoryId);lockOwner(c,user,generation);Settings old=settings(c,user,generation);expect(old,expected);
        try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_account_memories WHERE id=? AND user_id=? AND owner_generation=?")){q.setString(1,memoryId);q.setLong(2,user);q.setString(3,generation);if(q.executeUpdate()!=1)throw new StorageFailure(404,"MEMORY_NOT_FOUND","记忆不存在或已删除。 ");}
        return ModelGateway.JSON.createObjectNode().put("deleted",true).put("revision",advance(c,user,generation,old,old.enabled()));
    }
    // Editing is a user-authorized operation, never a tool exposed to the model.
    static ObjectNode edit(Connection c,long user,String generation,long expected,String memoryId,String value,byte[] key)throws SQLException,GeneralSecurityException{
        checkKey(key);id(memoryId);String text=validateText(value);lockOwner(c,user,generation);Settings old=settings(c,user,generation);expect(old,expected);
        String type;byte[] previous;
        try(PreparedStatement q=c.prepareStatement("SELECT kind,fingerprint FROM starhub_account_memories WHERE id=? AND user_id=? AND owner_generation=?")){
            q.setString(1,memoryId);q.setLong(2,user);q.setString(3,generation);try(ResultSet r=q.executeQuery()){
                if(!r.next())throw new StorageFailure(404,"MEMORY_NOT_FOUND","记忆不存在或已删除。 ");type=kind(r.getString(1));previous=r.getBytes(2);
            }
        }
        boolean semantic=Set.of("PREFERENCE","FACT").contains(type)&&text.equals(SemanticMemoryExtractor.eligibleInput(text));
        if(!semantic&&!MemoryExtractor.allowed(new MemoryExtractor.Candidate(type,text)))throw invalid();
        byte[] hash=fingerprint(key,user,generation,type,text);
        if(java.security.MessageDigest.isEqual(previous,hash))return ModelGateway.JSON.createObjectNode().put("edited",false).put("revision",old.revision());
        try(PreparedStatement q=c.prepareStatement("SELECT id FROM starhub_account_memories WHERE user_id=? AND owner_generation=? AND fingerprint=? AND id<>?")){
            q.setLong(1,user);q.setString(2,generation);q.setBytes(3,hash);q.setString(4,memoryId);try(ResultSet r=q.executeQuery()){if(r.next())throw new StorageFailure(409,"MEMORY_DUPLICATE","这条记忆已存在。 ");}
        }
        byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);byte[] cipher=AccountServlet.crypt(true,text.getBytes(StandardCharsets.UTF_8),key,nonce,aad(user,generation,memoryId,type));
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_account_memories SET fingerprint=?,nonce=?,ciphertext=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND owner_generation=?")){
            q.setBytes(1,hash);q.setBytes(2,nonce);q.setBytes(3,cipher);q.setString(4,memoryId);q.setLong(5,user);q.setString(6,generation);if(q.executeUpdate()!=1)throw conflict();
        }
        return ModelGateway.JSON.createObjectNode().put("edited",true).put("revision",advance(c,user,generation,old,old.enabled()));
    }
}
