package com.starhub;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AtomicWriterAdmissionTest {
    @Test void aConcurrentRequestWaitsThenAcquiresFreshAuthority()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            ExecutorService workers=Executors.newSingleThreadExecutor();
            try(var held=f.db.begin(UUID.randomUUID().toString())){
                CountDownLatch entered=new CountDownLatch(1);
                Future<?> next=workers.submit(()->{entered.countDown();return f.db.run(UUID.randomUUID().toString(),c->null);});
                assertTrue(entered.await(1,TimeUnit.SECONDS));Thread.sleep(50);assertFalse(next.isDone());held.close();next.get(2,TimeUnit.SECONDS);
                assertEquals(1,f.confirms.get());
            }finally{workers.shutdownNow();}
        }
    }
    @Test void deadlineAndInterruptionAreProvenBeforeAuthorityOrSql()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            AtomicInteger acquired=new AtomicInteger(),opened=new AtomicInteger();
            var authority=new AtomicDatabase.Authority(){
                public ReplicaAuthority.Lease acquire()throws java.sql.SQLException{acquired.incrementAndGet();return f.authority.acquire();}
                public void unchanged(ReplicaAuthority.Lease l)throws java.sql.SQLException{f.authority.unchanged(l);}
                public void confirm(ReplicaAuthority.Lease l,String g,String o)throws java.sql.SQLException{f.authority.confirm(l,g,o);}
            };
            var db=new AtomicDatabase(f.p,()->{opened.incrementAndGet();return f.connection();},authority);
            ExecutorService workers=Executors.newSingleThreadExecutor();
            try(var held=db.begin(UUID.randomUUID().toString())){
                Future<?> timeout=workers.submit(()->{StorageFailure e=assertThrows(StorageFailure.class,()->db.begin(UUID.randomUUID().toString(),30));assertEquals(429,e.status);assertEquals("ATOMIC_WRITER_BUSY",e.code);});timeout.get(1,TimeUnit.SECONDS);
                Future<?> interrupted=workers.submit(()->{Thread.currentThread().interrupt();try{assertEquals("ATOMIC_WRITER_BUSY",assertThrows(StorageFailure.class,()->db.begin(UUID.randomUUID().toString(),30)).code);assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}});interrupted.get(1,TimeUnit.SECONDS);
                assertEquals(1,acquired.get());assertEquals(1,opened.get());
            }finally{workers.shutdownNow();}
        }
    }
    @Test void closingWhileQueuedRejectsAfterLockWithoutNewTransaction()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try(var held=f.db.begin(UUID.randomUUID().toString())){
                Future<?> pending=workers.submit(()->{assertEquals("CLUSTER_UNAVAILABLE",assertThrows(StorageFailure.class,()->f.db.begin(UUID.randomUUID().toString())).code);});
                Thread.sleep(40);Future<Boolean> closing=workers.submit(f.db::closeAndDrain);Thread.sleep(40);held.close();pending.get(2,TimeUnit.SECONDS);assertTrue(closing.get(2,TimeUnit.SECONDS));assertEquals(0,f.confirms.get());
            }finally{workers.shutdownNow();}
        }
    }
}
