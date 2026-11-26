package com.starhub;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One public heavy provider/guest/workspace task per webapp JVM.
 * A busy response does not claim, dispatch, or alter any durable operation. */
final class ProviderAdmission {
    private static final Semaphore SLOT=new Semaphore(1,true);
    private static final Semaphore WAITERS=new Semaphore(3);
    private static final AtomicInteger CHAT_READS=new AtomicInteger();
    static Lease tryEnter(){return SLOT.tryAcquire()?new Lease(false):null;}
    static Lease enterChatRead(){
        CHAT_READS.incrementAndGet();
        if (SLOT.tryAcquire() || awaitPermit()) return new Lease(true);
        CHAT_READS.decrementAndGet();
        return null;
    }
    // A guest save has several HTTP stages. If a page read crosses one of
    // them, let that stage finish on its original request and memory budget.
    static Lease enterGuest(){
        Lease lease=tryEnter();
        if (lease!=null) return lease;
        if (CHAT_READS.get()==0) return tryEnter();
        return awaitPermit()?new Lease(false):null;
    }
    private static boolean awaitPermit() {
        if (!WAITERS.tryAcquire()) return false;
        try {
            return SLOT.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            WAITERS.release();
        }
    }
    static final class Lease implements AutoCloseable {
        private final AtomicBoolean closed=new AtomicBoolean();
        private final boolean chatRead;
        private Lease(boolean chatRead){this.chatRead=chatRead;}
        public void close(){
            if(!closed.compareAndSet(false,true))return;
            SLOT.release();
            if(chatRead)CHAT_READS.decrementAndGet();
        }
    }
    private ProviderAdmission(){}
}
