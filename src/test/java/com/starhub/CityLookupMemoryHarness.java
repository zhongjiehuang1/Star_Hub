package com.starhub;

import java.util.concurrent.*;

/** Run explicitly under a restricted heap when packaging the local gazetteer. */
public final class CityLookupMemoryHarness {
    public static void main(String[] args) throws Exception {
        if (CityLookup.find("安徽省滁州市").matches().isEmpty()) throw new AssertionError("missing Chuzhou");
        var pool = Executors.newFixedThreadPool(8);
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        var admitted = new java.util.concurrent.atomic.AtomicInteger();
        var refused = new java.util.concurrent.atomic.AtomicInteger();
        var tasks = new java.util.ArrayList<Future<?>>();
        for (int i = 0; i < 1000; i++) tasks.add(pool.submit(() -> {
            for (;;) {
                try {
                    if (CityLookup.find("Sheffield, UK").matches().isEmpty()) failures.incrementAndGet();
                    admitted.incrementAndGet();
                    break;
                } catch (CityLookup.Busy expected) { refused.incrementAndGet(); Thread.yield(); }
                catch (Exception failure) { failures.incrementAndGet(); break; }
            }
        }));
        for (var task : tasks) task.get();
        pool.shutdown();
        if (failures.get() != 0 || admitted.get() != 1000) throw new AssertionError("lookup failure");
        Runtime runtime = Runtime.getRuntime();
        System.out.printf("admitted=%d refused=%d heapUsedMiB=%.1f heapMaxMiB=%.1f%n", admitted.get(), refused.get(),
            (runtime.totalMemory()-runtime.freeMemory()) / 1048576.0, runtime.maxMemory() / 1048576.0);
    }
}
