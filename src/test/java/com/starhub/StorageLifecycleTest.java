package com.starhub;

import jakarta.servlet.*;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageLifecycleTest {
    @org.junit.jupiter.api.BeforeEach @AfterEach void clearActive()throws Exception{
        FileStorage.active=null;SingleDatabaseConnections.closePool();
        for(String name:java.util.List.of("boundRouting","boundPoolMode","poolRouting")){var f=SingleDatabaseConnections.class.getDeclaredField(name);f.setAccessible(true);f.set(null,null);}
        for(String name:java.util.List.of("nonSingleStarted","terminallyClosed","shutdownUnconfirmed")){var f=SingleDatabaseConnections.class.getDeclaredField(name);f.setAccessible(true);f.setBoolean(null,false);}
    }

    @Test void databaseStartupFailureRetriesWithoutReplacingAnExistingCoordinator()throws Exception{
        ServletContext context=mock(ServletContext.class);FileStorage service=mock(FileStorage.class);AtomicInteger calls=new AtomicInteger();
        StorageLifecycle lifecycle=new StorageLifecycle(()->{if(calls.incrementAndGet()==1)throw new SQLException("database still starting");return service;});
        lifecycle.attempt(context);assertNull(FileStorage.active);
        lifecycle.attempt(context);assertSame(service,FileStorage.active);
        lifecycle.attempt(context);assertEquals(2,calls.get());
        lifecycle.contextDestroyed(new ServletContextEvent(context));verify(service).close();assertNull(FileStorage.active);
        lifecycle.attempt(context);assertEquals(2,calls.get(),"shutdown cannot start another coordinator");
    }

    @Test void constructorCompletingDuringShutdownIsClosedAndNeverPublished()throws Exception{
        ServletContext context=mock(ServletContext.class);FileStorage service=mock(FileStorage.class);
        CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1),release=new CountDownLatch(1);
        StorageLifecycle lifecycle=new StorageLifecycle(()->{
            entered.countDown();
            while(true)try{release.await();break;}catch(InterruptedException expected){interrupted.countDown();}
            return service;
        });
        ServletContextEvent event=new ServletContextEvent(context);lifecycle.contextInitialized(event);
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        Thread shutdown=new Thread(()->lifecycle.contextDestroyed(event));shutdown.start();
        try{assertTrue(interrupted.await(2,TimeUnit.SECONDS));release.countDown();shutdown.join(3000);assertFalse(shutdown.isAlive());assertNull(FileStorage.active);verify(service).close();}
        finally{release.countDown();shutdown.join(3000);}
    }
    @Test void failedInitializationClosesOwnedPoolAndAllowsConfirmedRecovery()throws Exception {
        var config=SingleDatabaseTest.config();config.setProperty("storage.single.pool.enabled","true");
        for(String prefix:java.util.List.of("db.","storage.node2.db.","storage.objectStore.jdbc.")){config.setProperty(prefix+"user","synthetic");config.setProperty(prefix+"password","synthetic");}
        var source=new SingleDatabasePoolTest.FakeSource();try(var c=SingleDatabaseConnections.open(config,"db.url",source::open)){}
        ServletContext context=mock(ServletContext.class);StorageLifecycle lifecycle=new StorageLifecycle(()->{throw new SQLException("synthetic identity failure");});
        lifecycle.attempt(context);assertNull(FileStorage.active);assertTrue(source.closed.get()>=1,"failed initializer must close its owned physical pool");
        try(var recovered=SingleDatabaseConnections.open(config,"db.url",source::open)){assertTrue(source.opened.get()>=2,"confirmed shutdown can create a fresh pool");}
        lifecycle.contextDestroyed(new ServletContextEvent(context));
        assertThrows(SQLException.class,()->SingleDatabaseConnections.open(config,"db.url",source::open));
    }

}
