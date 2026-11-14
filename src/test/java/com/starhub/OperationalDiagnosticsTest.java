package com.starhub;

import static org.junit.jupiter.api.Assertions.*;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class OperationalDiagnosticsTest {
    @Test void failureClassificationDoesNotDependOnPotentiallySensitiveMessages() {
        assertEquals(OperationalDiagnostics.Reason.CAPACITY_TIMEOUT,OperationalDiagnostics.capacityReason(new TimeoutException("sensitive")));
        assertEquals(OperationalDiagnostics.Reason.CAPACITY_TIMEOUT,OperationalDiagnostics.capacityReason(new ExecutionException(new HttpTimeoutException("sensitive"))));
        assertEquals(OperationalDiagnostics.Reason.CAPACITY_INVALID_OR_UNAVAILABLE,OperationalDiagnostics.capacityReason(new Exception("sensitive")));
        assertEquals(OperationalDiagnostics.Reason.CAPACITY_HTTP_IO,OperationalDiagnostics.capacityReason(new ExecutionException(new java.io.IOException("sensitive"))));
    }
    @Test void recordsOnlyPredefinedCategories() {
        var reason=OperationalDiagnostics.Reason.DB_ADMISSION_TIMEOUT;long before=OperationalDiagnostics.count(reason);
        OperationalDiagnostics.record(reason);assertEquals(before+1,OperationalDiagnostics.count(reason));
    }
}
