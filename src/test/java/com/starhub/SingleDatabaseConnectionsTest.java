package com.starhub;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SingleDatabaseConnectionsTest {
    @org.junit.jupiter.api.BeforeEach @org.junit.jupiter.api.AfterEach void resetModeAfterTest()throws Exception {
        SingleDatabaseConnections.closePool();
        for(String name:java.util.List.of("boundRouting","boundPoolMode","poolRouting")){var f=SingleDatabaseConnections.class.getDeclaredField(name);f.setAccessible(true);f.set(null,null);}
        var legacy=SingleDatabaseConnections.class.getDeclaredField("nonSingleStarted");legacy.setAccessible(true);legacy.setBoolean(null,false);var terminal=SingleDatabaseConnections.class.getDeclaredField("terminallyClosed");terminal.setAccessible(true);terminal.setBoolean(null,false);var uncertain=SingleDatabaseConnections.class.getDeclaredField("shutdownUnconfirmed");uncertain.setAccessible(true);uncertain.setBoolean(null,false);
    }

    private Properties single(){Properties p=new Properties();p.setProperty("storage.topology","single-database");p.setProperty("storage.replicated","true");p.setProperty("storage.transactionMode","atomic");return p;}
    @Test void boundsPhysicalConnectionsAndReleasesOnce()throws Exception {
        Properties p=single();List<Connection> open=new ArrayList<>();AtomicInteger count=new AtomicInteger();
        try {
            for(int i=0;i<4;i++)open.add(SingleDatabaseConnections.open(p,()->{count.incrementAndGet();return mock(Connection.class);}));
            assertThrows(SQLTransientConnectionException.class,()->SingleDatabaseConnections.open(p,()->{count.incrementAndGet();return mock(Connection.class);}));
            assertEquals(4,count.get());open.get(0).close();open.get(0).close();
            try(Connection c=SingleDatabaseConnections.open(p,()->{count.incrementAndGet();return mock(Connection.class);})){assertEquals(5,count.get());assertThrows(SQLTransientConnectionException.class,()->SingleDatabaseConnections.open(p,()->mock(Connection.class)));}
        }finally{for(Connection c:open)c.close();}
    }
    @Test void openerFailureReleasesPermitAndGuardCannotBeUnwrapped()throws Exception {
        Properties p=single();for(int i=0;i<7;i++)assertThrows(SQLException.class,()->SingleDatabaseConnections.open(p,()->{throw new SQLException("synthetic");}));
        try(Connection c=SingleDatabaseConnections.open(p,()->mock(Connection.class))){assertThrows(SQLException.class,()->c.unwrap(Connection.class));}
    }
    @Test void absentPoolFlagKeepsBoundedDirectPath()throws Exception {
        Properties p=single();List<Connection> held=new ArrayList<>();AtomicInteger opened=new AtomicInteger();
        try {
            for(int i=0;i<4;i++)held.add(SingleDatabaseConnections.open(p,"db.url",()->{opened.incrementAndGet();return mock(Connection.class);}));
            assertThrows(SQLTransientConnectionException.class,()->SingleDatabaseConnections.open(p,"db.url",()->{opened.incrementAndGet();return mock(Connection.class);}));assertEquals(4,opened.get());
        }finally{for(Connection c:held)c.close();}
    }

    @Test void startupWarmupRetriesOnlyTransientConnectionEstablishmentAndReleasesAll()throws Exception {
        Properties p=single();p.setProperty("storage.single.pool.enabled","true");AtomicInteger calls=new AtomicInteger();List<Connection> created=new ArrayList<>();
        SingleDatabaseConnections.warmup(p,()->{if(calls.incrementAndGet()<=2)throw new SQLTransientConnectionException("synthetic cold pool");Connection c=mock(Connection.class);created.add(c);return c;});
        assertEquals(6,calls.get());assertEquals(4,created.size());for(Connection c:created)verify(c,times(1)).close();
    }
    @Test void warmupIsDisabledByDefaultAndDoesNotRetryPermanentFailure()throws Exception {
        Properties p=single();AtomicInteger calls=new AtomicInteger();SingleDatabaseConnections.warmup(p,()->{calls.incrementAndGet();return mock(Connection.class);});assertEquals(0,calls.get());
        p.setProperty("storage.single.pool.enabled","true");Connection first=mock(Connection.class);
        assertThrows(SQLException.class,()->SingleDatabaseConnections.warmup(p,()->{if(calls.getAndIncrement()==0)return first;throw new SQLException("permanent");}));
        assertEquals(2,calls.get());verify(first,times(1)).close();
    }
    @Test void warmupFailsClosedWhenReturnCleanupFails()throws Exception {
        Properties p=single();p.setProperty("storage.single.pool.enabled","true");Connection broken=mock(Connection.class);doThrow(new SQLException("synthetic reset failure")).when(broken).close();AtomicInteger calls=new AtomicInteger();
        assertThrows(SQLException.class,()->SingleDatabaseConnections.warmup(p,()->{calls.incrementAndGet();return broken;}));assertEquals(4,calls.get());verify(broken,times(4)).close();
    }

    @Test void warmupDeadlineCancelsWorkerClosesHeldConnectionsAndDisablesPool()throws Exception {
        Properties p=single();p.setProperty("storage.single.pool.enabled","true");AtomicInteger calls=new AtomicInteger();Connection held=mock(Connection.class);
        java.util.concurrent.CountDownLatch released=new java.util.concurrent.CountDownLatch(1);doAnswer(x->{released.countDown();return null;}).when(held).close();
        long started=System.nanoTime();SQLException error=assertThrows(SQLException.class,()->SingleDatabaseConnections.warmup(p,()->{
            if(calls.getAndIncrement()==0)return held;
            try{Thread.sleep(10_000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLTransientConnectionException("interrupted");}
            return mock(Connection.class);
        },150,500));
        assertTrue((System.nanoTime()-started)/1_000_000<1500);assertTrue(released.await(1,java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(error.getMessage().contains("pool closed"));assertThrows(SQLException.class,()->SingleDatabaseConnections.open(p,"db.url",()->mock(Connection.class)));
    }

    @Test void confirmedWarmupShutdownAllowsControlledSameRouteRetry()throws Exception {
        Properties p=single();p.setProperty("storage.single.pool.enabled","true");
        var mode=SingleDatabaseConnections.class.getDeclaredField("boundPoolMode");mode.setAccessible(true);mode.set(null,Boolean.TRUE);
        var routing=SingleDatabaseConnections.class.getDeclaredField("boundRouting");routing.setAccessible(true);routing.set(null,ReplicaAuthority.checkedRouting(p));
        assertThrows(SQLException.class,()->SingleDatabaseConnections.warmup(p,()->{try{Thread.sleep(10_000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLTransientConnectionException("interrupted");}return mock(Connection.class);},100,500));
        assertEquals(1,SingleDatabaseConnections.closePoolAfterFailure(true));AtomicInteger calls=new AtomicInteger();
        SingleDatabaseConnections.warmup(p,()->{calls.incrementAndGet();return mock(Connection.class);},1000,500);assertEquals(4,calls.get());
    }
    @Test void unconfirmedWorkerCannotReopenPoolUntilRestart()throws Exception {
        Properties p=single();p.setProperty("storage.single.pool.enabled","true");
        var mode=SingleDatabaseConnections.class.getDeclaredField("boundPoolMode");mode.setAccessible(true);mode.set(null,Boolean.TRUE);
        var routing=SingleDatabaseConnections.class.getDeclaredField("boundRouting");routing.setAccessible(true);routing.set(null,ReplicaAuthority.checkedRouting(p));
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1),finished=new java.util.concurrent.CountDownLatch(1);
        try {
            SQLException e=assertThrows(SQLException.class,()->SingleDatabaseConnections.warmup(p,()->{
                try{while(true){try{release.await();break;}catch(InterruptedException ignored){}}return mock(Connection.class);}finally{finished.countDown();}
            },100,150));
            assertTrue(e.getMessage().contains("shutdown unconfirmed"));assertEquals(2,SingleDatabaseConnections.closePoolAfterFailure(true));
            assertThrows(SQLException.class,()->SingleDatabaseConnections.open(p,"db.url",()->mock(Connection.class)));
        }finally{release.countDown();assertTrue(finished.await(1,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void shutdownFencesFuturePoolCreationEvenBeforeInitialization()throws Exception {
        Properties p=single();p.setProperty("storage.single.pool.enabled","true");assertEquals(1,SingleDatabaseConnections.terminateForShutdown());
        assertThrows(SQLException.class,()->SingleDatabaseConnections.open(p,"db.url",()->mock(Connection.class)));
    }

    @Test void shutdownFenceCoversDirectAndNonSingleModes()throws Exception {
        Properties single=single();AtomicInteger calls=new AtomicInteger();SingleDatabaseConnections.terminateForShutdown();
        assertThrows(SQLException.class,()->SingleDatabaseConnections.open(single,"db.url",()->{calls.incrementAndGet();return mock(Connection.class);}));
        assertThrows(SQLException.class,()->SingleDatabaseConnections.open(new Properties(),()->{calls.incrementAndGet();return mock(Connection.class);}));assertEquals(0,calls.get());
    }
    @Test void poolConstructionLockCannotBlockShutdownPastBudget()throws Exception {
        var field=SingleDatabaseConnections.class.getDeclaredField("POOL_LOCK");field.setAccessible(true);var lock=(java.util.concurrent.locks.ReentrantLock)field.get(null);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);Thread holder=new Thread(()->{lock.lock();try{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}finally{lock.unlock();}});holder.start();
        try {assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));var method=SingleDatabaseConnections.class.getDeclaredMethod("closePoolWithin",long.class);method.setAccessible(true);long started=System.nanoTime();
            assertEquals(Boolean.FALSE,method.invoke(null,120L));assertTrue((System.nanoTime()-started)/1_000_000<1200);
            Properties p=single();p.setProperty("storage.single.pool.enabled","true");assertThrows(SQLException.class,()->SingleDatabaseConnections.open(p,"db.url",()->mock(Connection.class)));
        }finally{release.countDown();holder.join(1000);assertFalse(holder.isAlive());}
    }

    @Test void shutdownDuringDirectOpenWaitsAndRejectsLateConnection()throws Exception {
        Properties p=single();var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var finished=new java.util.concurrent.CountDownLatch(1);var returned=new java.util.concurrent.atomic.AtomicBoolean();Connection raw=mock(Connection.class);
        Thread pending=new Thread(()->{try{SingleDatabaseConnections.open(p,"db.url",()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}return raw;});returned.set(true);}catch(SQLException expected){}finally{finished.countDown();}});pending.start();
        try {assertTrue(started.await(1,java.util.concurrent.TimeUnit.SECONDS));var method=SingleDatabaseConnections.class.getDeclaredMethod("closePoolWithin",long.class);method.setAccessible(true);assertEquals(Boolean.FALSE,method.invoke(null,120L));}
        finally{release.countDown();assertTrue(finished.await(1,java.util.concurrent.TimeUnit.SECONDS));pending.join(1000);}
        assertFalse(returned.get());verify(raw,times(1)).close();
    }

    @Test void shutdownWaitsForNonSingleOpenAndRejectsLateConnection()throws Exception {
        Properties legacy=new Properties();var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var finished=new java.util.concurrent.CountDownLatch(1);var returned=new java.util.concurrent.atomic.AtomicBoolean();Connection raw=mock(Connection.class);
        Thread pending=new Thread(()->{try{SingleDatabaseConnections.open(legacy,()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}return raw;});returned.set(true);}catch(SQLException expected){}finally{finished.countDown();}});pending.start();
        try {assertTrue(started.await(1,java.util.concurrent.TimeUnit.SECONDS));var method=SingleDatabaseConnections.class.getDeclaredMethod("closePoolWithin",long.class);method.setAccessible(true);assertEquals(Boolean.FALSE,method.invoke(null,120L));}
        finally{release.countDown();assertTrue(finished.await(1,java.util.concurrent.TimeUnit.SECONDS));pending.join(1000);}
        assertFalse(returned.get());verify(raw,times(1)).close();
    }

}
