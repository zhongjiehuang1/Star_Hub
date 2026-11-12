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
    @AfterEach void clearActive(){FileStorage.active=null;}

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
}
