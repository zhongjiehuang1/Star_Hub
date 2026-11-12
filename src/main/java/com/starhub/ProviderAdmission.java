package com.starhub;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** One public heavy provider/guest/workspace task per webapp JVM.
 * A busy response does not claim, dispatch, or alter any durable operation. */
final class ProviderAdmission {
    private static final Semaphore SLOT=new Semaphore(1);
    static Lease tryEnter(){return SLOT.tryAcquire()?new Lease():null;}
    static final class Lease implements AutoCloseable {
        private final AtomicBoolean closed=new AtomicBoolean();
        private Lease(){}
        public void close(){if(closed.compareAndSet(false,true))SLOT.release();}
    }
    private ProviderAdmission(){}
}
