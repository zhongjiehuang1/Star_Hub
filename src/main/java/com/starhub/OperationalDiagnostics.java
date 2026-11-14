package com.starhub;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/** Internal aggregate categories only. Never log exception messages, identifiers or request data. */
final class OperationalDiagnostics {
    enum Reason { DB_ADMISSION_TIMEOUT, DB_OPEN_FAILURE, POOL_ACQUIRE_FAILED, POOL_RESET_FAILED, CAPACITY_INTERRUPT, CAPACITY_TIMEOUT,
        CAPACITY_BAD_STATUS, CAPACITY_INVALID_OR_UNAVAILABLE, CAPACITY_CONFIG, CAPACITY_TEMP_FS_FAILURE,
        CAPACITY_HTTP_IO, CAPACITY_PARSE_DOCUMENT, CAPACITY_PARSE_IDENTITY, CAPACITY_PARSE_FRESHNESS,
        CAPACITY_PARSE_RESERVE, CAPACITY_PARSE_ESTIMATE, CAPACITY_PARSE_VOLUMES, ATOMIC_SQL_TRANSIENT,
        ATOMIC_SQL_OTHER, ATOMIC_UNEXPECTED, WORKSPACE_STORAGE_FAILURE, WORKSPACE_UNEXPECTED }
    private static final ConcurrentHashMap<Reason,LongAdder> COUNTS=new ConcurrentHashMap<>();
    private static final Logger LOG=Logger.getLogger(OperationalDiagnostics.class.getName());
    private OperationalDiagnostics() {}
    static void record(Reason reason) {
        LongAdder counter=COUNTS.computeIfAbsent(reason,ignored->new LongAdder());counter.increment();
        long count=counter.sum();
        if((count & (count-1))==0)LOG.warning("starhub_internal_diagnostic reason="+reason+" count="+count);
    }
    static Reason capacityReason(Throwable failure) {
        boolean io=false;
        for(int depth=0;failure!=null&&depth<8;depth++,failure=failure.getCause()){
            if(failure instanceof java.util.concurrent.TimeoutException||failure instanceof java.net.http.HttpTimeoutException)return Reason.CAPACITY_TIMEOUT;
            if(failure instanceof java.io.IOException)io=true;
        }
        return io?Reason.CAPACITY_HTTP_IO:Reason.CAPACITY_INVALID_OR_UNAVAILABLE;
    }
    static long count(Reason reason){LongAdder counter=COUNTS.get(reason);return counter==null?0:counter.sum();}
}
