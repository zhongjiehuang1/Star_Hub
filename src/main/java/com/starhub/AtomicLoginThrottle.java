package com.starhub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.Properties;

/** Durable per-IP failed-attempt accounting; a retry cannot charge the same attempt twice. */
final class AtomicLoginThrottle {
    record Attempt(String operation,byte[] identity,boolean fresh,int priorStatus) { }
    private AtomicLoginThrottle() { }
    private static byte[] mac(Properties p,String purpose,String value)throws Exception {
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(p.getProperty("storage.idempotency.secret").getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return mac.doFinal((purpose+"\n"+value).getBytes(StandardCharsets.UTF_8));
    }
    static Attempt begin(Properties p,String operation,String ip,String email,String password)throws Exception {
        return beginBound(p,operation,"login-ip-v1",ip==null?"unknown":ip,"login-request-v1",email+"\n"+password,mac(p,"login-email-v1",email),401);
    }
    static Attempt beginPasswordChange(Properties p,String operation,long user,String generation,String email,String current,String next)throws Exception {
        String actor=user+"\n"+generation;
        // The actor budget is global across JVMs, sessions and client addresses.
        // A changed IP must not turn the same uncertain operation into a new guess.
        return beginBound(p,operation,"password-change-actor-v1",actor,"password-change-request-v1",ModelGateway.JSON.writeValueAsString(java.util.List.of(user,generation,email,current,next)),mac(p,"password-change-owner-v1",actor),403);
    }
    private static Attempt beginBound(Properties p,String operation,String identityPurpose,String identityValue,String requestPurpose,String requestValue,byte[] lookupHash,int deniedStatus)throws Exception {
        FileStorage.operationId(operation);Connection c=AtomicDatabase.borrowed(p);if(c==null)throw new SQLException("Atomic login requires an outer request transaction");
        byte[] identity=mac(p,identityPurpose,identityValue),hash=mac(p,requestPurpose,requestValue);
        try(PreparedStatement q=c.prepareStatement("SELECT identity_hash,request_hash,outcome_status FROM starhub_login_attempts WHERE operation_id=?")){
            q.setString(1,operation);try(ResultSet r=q.executeQuery()){
                if(r.next()){
                    if(!MessageDigest.isEqual(hash,r.getBytes(2)))throw new StorageFailure(409,"OPERATION_CONFLICT","登录操作标识已用于其他请求，请先确认原请求结果。");
                    int status=r.getInt(3);if(status!=200&&status!=deniedStatus&&status!=429)throw StorageFailure.pending();
                    return new Attempt(operation,r.getBytes(1),false,status);
                }
            }
        }
        long now=System.currentTimeMillis();
        try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO starhub_login_limits(identity_hash,window_start_millis,failures) VALUES(?,?,0)")){q.setBytes(1,identity);q.setLong(2,now);q.executeUpdate();}
        int failures;long start;
        try(PreparedStatement q=c.prepareStatement("SELECT failures,window_start_millis FROM starhub_login_limits WHERE identity_hash=? FOR UPDATE")){
            q.setBytes(1,identity);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Login counter missing");failures=r.getInt(1);start=r.getLong(2);}
        }
        if(now-start>=900_000L){
            try(PreparedStatement q=c.prepareStatement("UPDATE starhub_login_limits SET failures=0,window_start_millis=? WHERE identity_hash=?")){q.setLong(1,now);q.setBytes(2,identity);q.executeUpdate();}failures=0;
        }
        int outcome=failures>=8?429:0;
        try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_login_attempts(operation_id,identity_hash,request_hash,email_hash,outcome_status) VALUES(?,?,?,?,?)")){
            q.setString(1,operation);q.setBytes(2,identity);q.setBytes(3,hash);q.setBytes(4,lookupHash);q.setInt(5,outcome);q.executeUpdate();
        }
        return new Attempt(operation,identity,true,outcome);
    }
    static com.fasterxml.jackson.databind.node.ObjectNode status(Properties p,String operation,String email,String ip)throws Exception {
        FileStorage.operationId(operation);Connection c=AtomicDatabase.borrowed(p);if(c==null)throw new SQLException("Atomic login status requires its request transaction");
        byte[] identity=mac(p,"login-status-ip-v1",ip==null?"unknown":ip);long now=System.currentTimeMillis();
        try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO starhub_login_status_limits(identity_hash,window_start_millis,requests) VALUES(?,?,0)")){q.setBytes(1,identity);q.setLong(2,now);q.executeUpdate();}
        long start;int count;
        try(PreparedStatement q=c.prepareStatement("SELECT window_start_millis,requests FROM starhub_login_status_limits WHERE identity_hash=? FOR UPDATE")){
            q.setBytes(1,identity);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Login status rate counter missing");start=r.getLong(1);count=r.getInt(2);}
        }
        if(now-start>=60_000L){try(PreparedStatement q=c.prepareStatement("UPDATE starhub_login_status_limits SET requests=0,window_start_millis=? WHERE identity_hash=?")){q.setLong(1,now);q.setBytes(2,identity);q.executeUpdate();}count=0;}
        if(count>=20)throw new StorageFailure(429,"RATE_LIMITED","查询次数过多，请稍后再试。");
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_login_status_limits SET requests=requests+1 WHERE identity_hash=?")){q.setBytes(1,identity);q.executeUpdate();}
        if(email==null||email.length()>254)throw new StorageFailure(404,"OPERATION_NOT_FOUND","操作不存在。");
        try(PreparedStatement q=c.prepareStatement("SELECT outcome_status FROM starhub_login_attempts WHERE operation_id=? AND email_hash=?")){
            q.setString(1,operation);q.setBytes(2,mac(p,"login-email-v1",email));try(ResultSet r=q.executeQuery()){
                if(!r.next())throw new StorageFailure(404,"OPERATION_NOT_FOUND","操作不存在。");int status=r.getInt(1);if(status!=200&&status!=401&&status!=429)throw StorageFailure.pending();
                return ModelGateway.JSON.createObjectNode().put("operationId",operation).put("state","COMMITTED").put("status",status);
            }
        }
    }
    static void complete(Properties p,Attempt attempt,boolean success)throws Exception {
        complete(p,attempt,success?200:401);
    }
    static void complete(Properties p,Attempt attempt,int status)throws Exception {
        if(status!=200&&status!=401&&status!=403)throw new IllegalArgumentException("Invalid authentication outcome");boolean success=status==200;
        if(!attempt.fresh||attempt.priorStatus!=0)return;Connection c=AtomicDatabase.borrowed(p);if(c==null)throw new SQLException("Atomic login scope missing");
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_login_attempts SET outcome_status=? WHERE operation_id=? AND outcome_status=0")){
            q.setInt(1,status);q.setString(2,attempt.operation);if(q.executeUpdate()!=1)throw new SQLException("Login outcome changed");
        }
        try(PreparedStatement q=c.prepareStatement(success?"UPDATE starhub_login_limits SET failures=0 WHERE identity_hash=?":"UPDATE starhub_login_limits SET failures=failures+1 WHERE identity_hash=?")){
            q.setBytes(1,attempt.identity);if(q.executeUpdate()!=1)throw new SQLException("Login counter changed");
        }
    }
}
