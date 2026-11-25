package com.starhub;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountIdleSessionsTest {
    private static final String GENERATION="12345678-1234-1234-1234-123456789abc";
    private static Connection db(String name)throws Exception {
        Connection c=DriverManager.getConnection("jdbc:h2:mem:"+name+";DB_CLOSE_DELAY=-1;MODE=MySQL");
        try(Statement q=c.createStatement()){
            q.execute("CREATE TABLE IF NOT EXISTS starhub_account_idle_gate(id TINYINT PRIMARY KEY,last_clock_millis BIGINT NOT NULL)");
            q.execute("INSERT INTO starhub_account_idle_gate(id,last_clock_millis) SELECT 1,0 WHERE NOT EXISTS (SELECT 1 FROM starhub_account_idle_gate WHERE id=1)");
            q.execute("CREATE TABLE IF NOT EXISTS starhub_account_idle_sessions(session_nonce CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,user_generation CHAR(36) NOT NULL,last_activity_millis BIGINT NOT NULL,revoked TINYINT NOT NULL)");
        }
        c.setAutoCommit(false);return c;
    }
    private static String id(){return UUID.randomUUID().toString();}
    private static void age(Connection c,String id,long millis)throws Exception{
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_account_idle_sessions SET last_activity_millis=? WHERE session_nonce=?")){
            q.setLong(1,AccountIdleSessions.databaseNow(c)-millis);q.setString(2,id);q.executeUpdate();
        }
        c.commit();
    }
    @Test void expiredOrRevokedSessionsCannotBeRevivedOrReplayed()throws Exception{
        try(Connection c=db(id())){
            String nonce=id();assertEquals(AccountIdleSessions.IDLE_MILLIS,AccountIdleSessions.createOrRecover(c,nonce,1,GENERATION,true));c.commit();
            assertTrue(AccountIdleSessions.touch(c,nonce,1,GENERATION,1000)>590_000);c.commit();
            age(c,nonce,AccountIdleSessions.IDLE_MILLIS+1);
            assertEquals(0,AccountIdleSessions.remaining(c,nonce,1,GENERATION));c.commit();
            assertEquals(0,AccountIdleSessions.touch(c,nonce,1,GENERATION,0));
            assertEquals(0,AccountIdleSessions.createOrRecover(c,nonce,1,GENERATION,false));c.commit();
            String next=id();AccountIdleSessions.createOrRecover(c,next,1,GENERATION,true);c.commit();
            assertEquals(0,AccountIdleSessions.createOrRecover(c,nonce,1,GENERATION,false));c.commit();
            AccountIdleSessions.revoke(c,next,1,GENERATION);c.commit();
            assertEquals(0,AccountIdleSessions.remaining(c,next,1,GENERATION));
            assertEquals(0,AccountIdleSessions.createOrRecover(c,next,1,GENERATION,false));c.commit();
        }
    }
    @Test void loginRecoveryDoesNotRenewTheOriginalActivityAndCannotChangeOwner()throws Exception{
        try(Connection c=db(id())){
            String nonce=id();AccountIdleSessions.createOrRecover(c,nonce,1,GENERATION,true);c.commit();age(c,nonce,120_000);
            long before=AccountIdleSessions.remaining(c,nonce,1,GENERATION),recovered=AccountIdleSessions.createOrRecover(c,nonce,1,GENERATION,false);c.commit();
            assertTrue(recovered<=before&&recovered>470_000);
            assertEquals(0,AccountIdleSessions.remaining(c,nonce,2,GENERATION));
            assertEquals(0,AccountIdleSessions.touch(c,nonce,2,GENERATION,0));c.rollback();
            assertEquals(409,assertThrows(StorageFailure.class,()->AccountIdleSessions.createOrRecover(c,nonce,1,GENERATION,true)).status);c.rollback();
        }
    }
    @Test void activityAgeIsBoundedAndStoredMonotonically()throws Exception{
        try(Connection c=db(id())){
            String nonce=id();AccountIdleSessions.createOrRecover(c,nonce,1,GENERATION,true);c.commit();
            assertThrows(IllegalArgumentException.class,()->AccountIdleSessions.touch(c,nonce,1,GENERATION,5001));
            assertThrows(IllegalArgumentException.class,()->AccountIdleSessions.touch(c,nonce,1,GENERATION,-1));
            age(c,nonce,3000);long first=AccountIdleSessions.touch(c,nonce,1,GENERATION,0);c.commit();
            long second=AccountIdleSessions.touch(c,nonce,1,GENERATION,5000);c.commit();assertTrue(second>=first-1000);
        }
    }
    @Test void observedExpiryIsRevokedAndClockRollbackFailsClosed()throws Exception{
        try(Connection c=db(id())){
            String nonce=id();AccountIdleSessions.createOrRecover(c,nonce,1,GENERATION,true);c.commit();
            age(c,nonce,AccountIdleSessions.IDLE_MILLIS+1);assertEquals(0,AccountIdleSessions.remaining(c,nonce,1,GENERATION));c.commit();
            age(c,nonce,0);assertEquals(0,AccountIdleSessions.touch(c,nonce,1,GENERATION,0));c.commit();
            try(PreparedStatement q=c.prepareStatement("UPDATE starhub_account_idle_gate SET last_clock_millis=? WHERE id=1")){
                q.setLong(1,AccountIdleSessions.databaseNow(c)+60_000);q.executeUpdate();
            }c.commit();
            var failure=assertThrows(StorageFailure.class,()->AccountIdleSessions.remaining(c,nonce,1,GENERATION));
            assertEquals(503,failure.status);assertEquals("SESSION_CLOCK_UNAVAILABLE",failure.code);c.rollback();
        }
    }
    @Test void twoConnectionsSerializeAndExpiredTransactionsCannotBeRevived()throws Exception{
        String name=id();try(Connection first=db(name);Connection second=db(name)){
            String nonce=id();AccountIdleSessions.createOrRecover(first,nonce,1,GENERATION,true);first.commit();age(first,nonce,AccountIdleSessions.IDLE_MILLIS+1);
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try{
                Callable<Boolean> work=()->{try(Connection c=DriverManager.getConnection("jdbc:h2:mem:"+name+";DB_CLOSE_DELAY=-1;MODE=MySQL")){c.setAutoCommit(false);boolean live=AccountIdleSessions.touch(c,nonce,1,GENERATION,0)>0;c.commit();return live;}};
                var a=pool.submit(work);var b=pool.submit(work);assertFalse(a.get(5,TimeUnit.SECONDS));assertFalse(b.get(5,TimeUnit.SECONDS));
            }finally{pool.shutdownNow();}
        }
    }
}
