package com.starhub;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** H2 with a minimal MySQL catalog/session adapter; real MySQL validation is a separate test. */
class SingleDatabasePoolTest {
    @org.junit.jupiter.api.BeforeEach @org.junit.jupiter.api.AfterEach void resetModeAfterTest()throws Exception {
        SingleDatabaseConnections.closePool();
        for(String name:java.util.List.of("boundRouting","boundPoolMode","poolRouting")){var f=SingleDatabaseConnections.class.getDeclaredField(name);f.setAccessible(true);f.set(null,null);}
        var legacy=SingleDatabaseConnections.class.getDeclaredField("nonSingleStarted");legacy.setAccessible(true);legacy.setBoolean(null,false);var terminal=SingleDatabaseConnections.class.getDeclaredField("terminallyClosed");terminal.setAccessible(true);terminal.setBoolean(null,false);var uncertain=SingleDatabaseConnections.class.getDeclaredField("shutdownUnconfirmed");uncertain.setAccessible(true);uncertain.setBoolean(null,false);
    }

    static final class FakeSource implements SingleDatabasePool.Source {
        final String url="jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        final AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();final AtomicBoolean failCommit=new AtomicBoolean();
        public Connection open()throws SQLException {
            Connection c=DriverManager.getConnection(url);opened.incrementAndGet();String[] catalog={"accounts"};
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                String n=m.getName();if(n.equals("close"))closed.incrementAndGet();if(n.equals("setCatalog")){catalog[0]=(String)a[0];return null;}if(n.equals("getCatalog"))return catalog[0];
                if(n.equals("commit")&&failCommit.getAndSet(false))throw new SQLException("synthetic unknown outcome","08006");
                try {Object v=m.invoke(c,a);if(n.equals("createStatement")){
                    Statement st=(Statement)v;return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(x,k,z)->{
                        if(z!=null&&z.length>0&&z[0] instanceof String sql){
                            Object[] copy=z.clone();if(sql.equals("SELECT DATABASE()"))copy[0]="SELECT '"+catalog[0]+"'";
                            if(sql.equals("SET SESSION time_zone='+00:00'"))copy[0]="SELECT 1";
                            if(sql.equals("SELECT DATABASE(),@@SESSION.sql_log_bin,@@SESSION.time_zone"))copy[0]="SELECT '"+catalog[0]+"',1,'+00:00'";
                            z=copy;
                        }
                        try{return k.invoke(st,z);}catch(InvocationTargetException e){throw e.getCause();}
                    });
                }return v;}catch(InvocationTargetException e){throw e.getCause();}
            });
        }
    }
    @Test void reusesOnePhysicalPoolAcrossThreeCatalogsAndRollsBack()throws Exception {
        FakeSource f=new FakeSource();try(SingleDatabasePool pool=new SingleDatabasePool(f,"accounts",Set.of("accounts","files","decisions"))){
            try(Connection c=pool.open("accounts")){c.createStatement().execute("CREATE TABLE IF NOT EXISTS t(id INT)");c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);c.createStatement().execute("INSERT INTO t VALUES(1)");}
            try(Connection c=pool.open("files")){assertEquals("files",c.getCatalog());try(ResultSet r=c.createStatement().executeQuery("SELECT COUNT(*) FROM t")){assertTrue(r.next());assertEquals(0,r.getInt(1));}}
            try(Connection c=pool.open("decisions")){assertEquals("decisions",c.getCatalog());assertTrue(c.getAutoCommit());assertFalse(c.isReadOnly());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,c.getTransactionIsolation());}
            assertEquals(1,f.opened.get());assertThrows(SQLException.class,()->pool.open("other"));
        }
    }
    @Test void capsAllCatalogsTogetherAndEvictsUnknownCommit()throws Exception {
        FakeSource f=new FakeSource();try(SingleDatabasePool pool=new SingleDatabasePool(f,"accounts",Set.of("accounts","files","decisions"))){
            List<Connection> leases=new ArrayList<>();try{
                for(String name:List.of("accounts","files","decisions","accounts"))leases.add(pool.open(name));
                assertThrows(SQLException.class,()->pool.open("files"));assertEquals(4,f.opened.get());
            }finally{for(Connection c:leases)c.close();}
            try(Connection c=pool.open("files")){c.setAutoCommit(false);f.failCommit.set(true);assertThrows(SQLException.class,c::commit);}
            List<Connection> again=new ArrayList<>();try{for(int i=0;i<4;i++)again.add(pool.open("accounts"));assertEquals(5,f.opened.get(),"Failed commit must evict a physical connection");}finally{for(Connection c:again)c.close();}
        }
    }
    @Test void configMismatchFailsBeforeAnyPhysicalConnection()throws Exception {
        Properties p=SingleDatabaseTest.config();p.setProperty("storage.single.pool.enabled","true");
        for(String prefix:List.of("db.","storage.node2.db.","storage.objectStore.jdbc.")){p.setProperty(prefix+"user","synthetic");p.setProperty(prefix+"password","synthetic");}
        AtomicInteger opened=new AtomicInteger();
        try {
            p.setProperty("storage.node2.db.password","different");
            assertThrows(SQLException.class,()->SingleDatabaseConnections.open(p,"db.url",()->{opened.incrementAndGet();throw new SQLException();}));assertEquals(0,opened.get());
            p.setProperty("storage.node2.db.password","synthetic");p.setProperty("storage.node2.db.url","jdbc:mysql://127.0.0.1:332/b?sslMode=VERIFY_IDENTITY&connectTimeout=999");
            // Controlled connectTimeout is normalized by the production JDBC route.
            p.setProperty("storage.objectStore.jdbc.url","jdbc:mysql://127.0.0.1:332/decisions?sslMode=VERIFY_IDENTITY&characterEncoding=UTF-8");
            assertThrows(SQLException.class,()->SingleDatabaseConnections.open(p,"db.url",()->{opened.incrementAndGet();throw new SQLException();}));assertEquals(0,opened.get());
        }finally{SingleDatabaseConnections.closePool();}
    }
    @Test void runningPoolRejectsRouteChangesBeforeBorrow()throws Exception {
        Properties p=SingleDatabaseTest.config();p.setProperty("storage.single.pool.enabled","true");
        for(String prefix:List.of("db.","storage.node2.db.","storage.objectStore.jdbc.")){p.setProperty(prefix+"user","synthetic");p.setProperty(prefix+"password","synthetic");}
        FakeSource f=new FakeSource();try {
            try(Connection c=SingleDatabaseConnections.open(p,"db.url",f::open)){assertEquals("a",c.getCatalog());}
            int before=f.opened.get();p.setProperty("storage.objectStore.jdbc.password","rotated");
            assertThrows(SQLException.class,()->SingleDatabaseConnections.open(p,"db.url",f::open));assertEquals(before,f.opened.get());
        }finally{SingleDatabaseConnections.closePool();}
    }

    @Test void switchingFromPoolToDirectIsRejectedEvenWhenPoolIdle()throws Exception {
        Properties p=SingleDatabaseTest.config();p.setProperty("storage.single.pool.enabled","true");
        for(String prefix:List.of("db.","storage.node2.db.","storage.objectStore.jdbc.")){p.setProperty(prefix+"user","synthetic");p.setProperty(prefix+"password","synthetic");}
        FakeSource f=new FakeSource();try(Connection c=SingleDatabaseConnections.open(p,"db.url",f::open)){}
        Properties other=(Properties)p.clone();other.setProperty("storage.single.pool.enabled","false");
        assertThrows(SQLException.class,()->SingleDatabaseConnections.open(other,"db.url",f::open));
        other.remove("storage.single.pool.enabled");assertThrows(SQLException.class,()->SingleDatabaseConnections.open(other,"db.url",f::open));
        assertThrows(SQLException.class,()->SingleDatabaseConnections.open(p,f::open));
    }
    @Test void switchingFromDirectToPoolWhileConnectionLiveIsRejected()throws Exception {
        Properties p=SingleDatabaseTest.config();p.setProperty("storage.single.pool.enabled","false");
        for(String prefix:List.of("db.","storage.node2.db.","storage.objectStore.jdbc.")){p.setProperty(prefix+"user","synthetic");p.setProperty(prefix+"password","synthetic");}
        FakeSource f=new FakeSource();try(Connection c=SingleDatabaseConnections.open(p,"db.url",f::open)){
            Properties other=(Properties)p.clone();other.setProperty("storage.single.pool.enabled","true");
            assertThrows(SQLException.class,()->SingleDatabaseConnections.open(other,"db.url",f::open));assertEquals(1,f.opened.get());
        }
    }
    @Test void decisionStoreRejectsCredentialsDifferentFromFrozenRoute()throws Exception {
        Properties p=new Properties();p.setProperty("storage.objectStore.jdbc.user","expected");p.setProperty("storage.objectStore.jdbc.password","expected-secret");
        assertDoesNotThrow(()->JdbcDecisionStore.requireExpectedCredentials(p,"expected","expected-secret"));
        assertThrows(SQLException.class,()->JdbcDecisionStore.requireExpectedCredentials(p,"other","expected-secret"));
        assertThrows(SQLException.class,()->JdbcDecisionStore.requireExpectedCredentials(p,"expected","other"));
    }

    @Test void fourConcurrentBorrowersCanUseAnAlreadyWarmPool()throws Exception {
        Properties p=SingleDatabaseTest.config();p.setProperty("storage.single.pool.enabled","true");
        for(String prefix:List.of("db.","storage.node2.db.","storage.objectStore.jdbc.")){p.setProperty(prefix+"user","synthetic");p.setProperty(prefix+"password","synthetic");}
        FakeSource source=new FakeSource();SingleDatabaseConnections.warmup(p,()->SingleDatabaseConnections.open(p,"db.url",source::open),3000,1000);
        var start=new java.util.concurrent.CountDownLatch(1);var acquired=new java.util.concurrent.CountDownLatch(4);var release=new java.util.concurrent.CountDownLatch(1);var executor=java.util.concurrent.Executors.newFixedThreadPool(4);List<java.util.concurrent.Future<?>> tasks=new ArrayList<>();
        try {
            for(int i=0;i<4;i++)tasks.add(executor.submit(()->{start.await();try(Connection c=SingleDatabaseConnections.open(p,"db.url",source::open)){acquired.countDown();if(!release.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new SQLException("test release timed out");}return null;}));
            start.countDown();assertTrue(acquired.await(2,java.util.concurrent.TimeUnit.SECONDS),"warm pool must not reject concurrent borrowers due to its initialization lock");
        }finally{release.countDown();for(var task:tasks)task.get(3,java.util.concurrent.TimeUnit.SECONDS);executor.shutdownNow();}
    }

}
