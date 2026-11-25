package com.starhub;

import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual H2 mutations and simulated capacity-boundary tests; not physical capacity acceptance. */
class AtomicChunkBudgetTest {
    private static long cost(long bytes){return 2*bytes+1024;}
    private static long required(long incoming){return cost(incoming)+cost(0)+cost(AtomicDatabase.MAX_COMPETING_TRANSACTION_BYTES);}

    @Test void oneScopeCanActuallyCommitThreeMiBAcrossTwoImmutableRows()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            byte[] bytes=new byte[AtomicDatabase.MAX_CHUNK_TRANSACTION_BYTES];new Random(871).nextBytes(bytes);
            String operation=UUID.randomUUID().toString();var plan=f.files.payloads.plan(UUID.randomUUID().toString(),operation,"TEST","owner","binding",bytes);
            f.db.run(operation,c->{ChunkedPayloadStore.reserve(c,plan);ChunkedPayloadStore.putChunk(c,plan,0,Arrays.copyOfRange(bytes,0,ChunkedPayloadStore.CHUNK_BYTES));ChunkedPayloadStore.putChunk(c,plan,1,Arrays.copyOfRange(bytes,ChunkedPayloadStore.CHUNK_BYTES,bytes.length));ChunkedPayloadStore.seal(c,plan);ChunkedPayloadStore.publish(c,plan);return null;});
            assertEquals(2,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
            try(Connection c=f.connection()){assertArrayEquals(bytes,ChunkedPayloadStore.readBytes(c,plan,bytes.length));}
        }
    }
    @Test void fileReservationIncludesTheLargestLegalCompetingScopeAtTheExactBoundary()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            var metadata=ModelGateway.JSON.createArrayNode().add(new FileStorage.Upload("one.txt","text/plain",new byte[]{1}).metadata());
            String operation=UUID.randomUUID().toString();long peak=required(1);f.availableBytes.set(StorageCluster.FLOOR+peak-1);
            assertEquals(507,assertThrows(StorageFailure.class,()->f.files.reserve(1,operation,metadata,1)).status);assertEquals(0,f.count("SELECT COUNT(*) FROM file_storage_operations"));
            f.availableBytes.incrementAndGet();assertEquals("RESERVED",f.files.reserve(1,operation,metadata,1).path("state").asText());assertEquals(1,f.count("SELECT reserved_bytes FROM file_storage_users"));
        }
    }
    @Test void guestTransientAdmissionIncludesTheSameFullCompetingScope()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            f.availableBytes.set(StorageCluster.FLOOR+required(0)-1);
            assertEquals(507,assertThrows(StorageFailure.class,()->f.db.run(UUID.randomUUID().toString(),c->{f.files.transientCapacity(c,0);return null;})).status);
            f.availableBytes.incrementAndGet();assertDoesNotThrow(()->f.db.run(UUID.randomUUID().toString(),c->{f.files.transientCapacity(c,0);return null;}));
        }
    }
    @Test void providerCannotDispatchIfOnlyTheSmallerSingleRowCompetitorFits()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            String operation=UUID.randomUUID().toString();var input=h.input(operation);h.operations.claim(h.owner,operation,"test:generate",input);
            long retained=h.f.count("SELECT SUM(reserved_bytes) FROM starhub_provider_capacity");
            h.f.availableBytes.set(StorageCluster.FLOOR+required(ProviderOperations.RESULT_LIMIT+28L)-1);
            assertEquals(507,assertThrows(StorageFailure.class,()->h.operations.dispatch(h.owner,operation,"test:generate",input)).status);assertEquals("CLAIMED",h.operations.status(h.owner,operation).state());assertEquals(retained,h.f.count("SELECT SUM(reserved_bytes) FROM starhub_provider_capacity"));
            h.f.availableBytes.incrementAndGet();assertNotNull(h.operations.dispatch(h.owner,operation,"test:generate",input));assertEquals("SENT",h.operations.status(h.owner,operation).state());
        }
    }
}
