package com.starhub;

import java.sql.*;
import java.util.Objects;

/** Shared, non-revivable account-session activity state. Callers own the transaction. */
final class AccountIdleSessions {
    static final String ATTRIBUTE="starhub.account.idleNonce";
    static final long IDLE_MILLIS=600_000L;
    static final int MAX_TOTAL=4096, MAX_PER_USER=64, MAX_ACTIVITY_AGE_MILLIS=5000;
    // Conservative upper bound for one login plus bounded cleanup, pending measured validation.
    static final long LOGIN_PHYSICAL_BOUND=65_536L+MAX_TOTAL*8192L;
    private AccountIdleSessions() {}

    private static void transaction(Connection c)throws SQLException {
        if(c.getAutoCommit())throw new SQLException("Account idle sessions require an explicit transaction");
    }
    private static long gate(Connection c)throws SQLException {
        long previous;
        try(PreparedStatement q=c.prepareStatement("SELECT last_clock_millis FROM starhub_account_idle_gate WHERE id=1 FOR UPDATE");ResultSet r=q.executeQuery()){
            if(!r.next())throw new SQLException("Account idle session migration is required");previous=r.getLong(1);
        }
        long now=databaseNow(c);
        if(now<previous)throw new StorageFailure(503,"SESSION_CLOCK_UNAVAILABLE","登录状态暂不可确认，请稍后重试。");
        if(now>previous)try(PreparedStatement q=c.prepareStatement("UPDATE starhub_account_idle_gate SET last_clock_millis=? WHERE id=1")){
            q.setLong(1,now);if(q.executeUpdate()!=1)throw new SQLException("Account idle clock changed");
        }
        return now;
    }
    static long databaseNow(Connection c)throws SQLException {
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT CURRENT_TIMESTAMP(3)")){
            if(!r.next())throw new SQLException("Database clock unavailable");Timestamp value=r.getTimestamp(1);
            if(value==null)throw new SQLException("Database clock unavailable");return value.getTime();
        }
    }
    private record Row(long user,String generation,long activity,boolean revoked) {}
    private static Row row(Connection c,String nonce,boolean lock)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT user_id,user_generation,last_activity_millis,revoked FROM starhub_account_idle_sessions WHERE session_nonce=?"+(lock?" FOR UPDATE":""))){
            q.setString(1,nonce);try(ResultSet r=q.executeQuery()){return r.next()?new Row(r.getLong(1),r.getString(2),r.getLong(3),r.getInt(4)!=0):null;}
        }
    }
    private static boolean live(Row row,long user,String generation,long now){
        return row!=null&&!row.revoked&&row.user==user&&Objects.equals(row.generation,generation)&&row.activity<=now&&now-row.activity<IDLE_MILLIS;
    }
    private static void markExpired(Connection c,String nonce,Row row,long user,String generation,long now)throws SQLException {
        if(row==null||row.revoked||row.user!=user||!Objects.equals(row.generation,generation)||row.activity>now||now-row.activity<IDLE_MILLIS)return;
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_account_idle_sessions SET revoked=1 WHERE session_nonce=? AND user_id=? AND user_generation=?")){
            q.setString(1,nonce);q.setLong(2,user);q.setString(3,generation);q.executeUpdate();
        }
    }
    // A zero return must be committed before reporting an expired session.
    static long remaining(Connection c,String nonce,long user,String generation)throws SQLException {
        if(nonce==null||generation==null||user<1)return 0;
        transaction(c);long now=gate(c);Row value=row(c,nonce,true);
        if(live(value,user,generation,now))return IDLE_MILLIS-(now-value.activity);
        markExpired(c,nonce,value,user,generation,now);return 0;
    }
    static long createOrRecover(Connection c,String nonce,long user,String generation,boolean firstAttempt)throws SQLException {
        transaction(c);FileStorage.operationId(nonce);if(user<1||generation==null||generation.length()!=36)throw new IllegalArgumentException("Invalid account identity");
        long now=gate(c);Row existing=row(c,nonce,true);
        if(!firstAttempt){if(!live(existing,user,generation,now)){markExpired(c,nonce,existing,user,generation,now);return 0;}return IDLE_MILLIS-(now-existing.activity);}
        if(existing!=null)throw new StorageFailure(409,"SESSION_OPERATION_CONFLICT","登录请求编号已用于其他会话。");
        // The total table is capped; a single bounded deletion prevents expired
        // rows from trapping accounts at the quota. Physical cost is admitted by caller.
        try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_account_idle_sessions WHERE revoked<>0 OR last_activity_millis<=?")){q.setLong(1,now-IDLE_MILLIS);q.executeUpdate();}
        try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*),SUM(CASE WHEN user_id=? AND user_generation=? THEN 1 ELSE 0 END) FROM starhub_account_idle_sessions")){
            q.setLong(1,user);q.setString(2,generation);try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong(1)>=MAX_TOTAL||r.getLong(2)>=MAX_PER_USER)throw new StorageFailure(429,"SESSION_CAPACITY","登录会话数量已达上限，请稍后重试。");}
        }
        try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_account_idle_sessions(session_nonce,user_id,user_generation,last_activity_millis,revoked) VALUES(?,?,?,?,0)")){
            q.setString(1,nonce);q.setLong(2,user);q.setString(3,generation);q.setLong(4,now);q.executeUpdate();
        }
        return IDLE_MILLIS;
    }
    static long touch(Connection c,String nonce,long user,String generation,int ageMillis)throws SQLException {
        transaction(c);if(ageMillis<0||ageMillis>MAX_ACTIVITY_AGE_MILLIS)throw new IllegalArgumentException("Invalid activity age");
        FileStorage.operationId(nonce);long now=gate(c);Row old=row(c,nonce,true);if(!live(old,user,generation,now)){markExpired(c,nonce,old,user,generation,now);return 0;}
        long activity=Math.max(old.activity,now-ageMillis);
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_account_idle_sessions SET last_activity_millis=? WHERE session_nonce=? AND user_id=? AND user_generation=? AND revoked=0")){
            q.setLong(1,activity);q.setString(2,nonce);q.setLong(3,user);q.setString(4,generation);if(q.executeUpdate()!=1)throw ended();
        }
        return IDLE_MILLIS-(now-activity);
    }
    static void revoke(Connection c,String nonce,long user,String generation)throws SQLException {
        transaction(c);if(nonce==null||generation==null)return;FileStorage.operationId(nonce);gate(c);
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_account_idle_sessions SET revoked=1 WHERE session_nonce=? AND user_id=? AND user_generation=?")){
            q.setString(1,nonce);q.setLong(2,user);q.setString(3,generation);q.executeUpdate();
        }
    }
    private static StorageFailure ended(){return new StorageFailure(401,"SESSION_ENDED","登录会话已结束，请重新登录原账户。");}
}
