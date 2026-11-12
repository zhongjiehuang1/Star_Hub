package com.starhub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real local SQL; GTID/controller/capacity are explicit adapters. No provider network is called. */
class ProviderOperationsTest {
    static final class Harness implements AutoCloseable {
        final AtomicTransactionsTest.Fixture f=new AtomicTransactionsTest.Fixture();final ProviderOperations operations;
        final ProviderOperations.Owner owner=new ProviderOperations.Owner("synthetic-owner:1:tab",c->{});
        Harness()throws Exception{
            try(Connection c=f.connection();Statement q=c.createStatement()){
                String ddl=Files.readString(Path.of("deploy/migrations/012_chunked_payload_store.sql")).replaceAll("(?m)^--.*$","");
                for(String sql:ddl.split(";"))if(!sql.isBlank())q.execute(sql);
                q.execute("CREATE TABLE IF NOT EXISTS starhub_provider_payloads(operation_id CHAR(36) PRIMARY KEY,dispatch_hash BINARY(32),response_status SMALLINT,terminal_state VARCHAR(32),plan_json TEXT,cipher_binding VARCHAR(200))");
            }
            f.p.setProperty("vault.key",Base64.getEncoder().encodeToString(new byte[32]));operations=other(f.db);
        }
        ProviderOperations other(AtomicDatabase db)throws Exception{return new ProviderOperations(db,f.cluster(),f.p);}
        ProviderOperations other()throws Exception{return other(new AtomicDatabase(f.p,f::connection,f.authority));}
        ObjectNode input(String id){return ModelGateway.JSON.createObjectNode().put("operationId",id).put("provider","synthetic-test-only").put("apiKey","test-only-not-a-real-api-key").put("prompt","test-only-private-input");}
        ProviderOperations.Ticket ticket(String id,ObjectNode request)throws Exception{assertEquals("CLAIMED",operations.claim(owner,id,"test:generate",request).state());return operations.dispatch(owner,id,"test:generate",request);}
        public void close(){f.close();}
    }
    @Test void unknownClaimAcknowledgementCanResumeTheOriginalIdAndCompleteOnce()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();ObjectNode input=h.input(id);h.f.loseCommit.set(true);assertThrows(SQLException.class,()->h.operations.claim(h.owner,id,"test:generate",input));
            assertEquals("CLAIMED",h.other().claim(h.owner,id,"test:generate",input).state());var ticket=h.operations.dispatch(h.owner,id,"test:generate",input);AtomicInteger effects=new AtomicInteger();
            try(AutoCloseable context=ticket.enter()){ProviderOperations.beforeExternal();effects.incrementAndGet();}ticket.complete(200,ModelGateway.JSON.createObjectNode().put("text","test-only-result"));
            assertEquals("COMPLETED",h.other().claim(h.owner,id,"test:generate",input).state());assertThrows(StorageFailure.class,()->h.other().dispatch(h.owner,id,"test:generate",input));assertEquals(1,effects.get());assertEquals(0,h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));
        }
    }
    @Test void unknownSentCommitNeverAuthorizesAnExternalRetryEvenIfNoCallWasMadeYet()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();ObjectNode request=h.input(id);h.operations.claim(h.owner,id,"test:generate",request);h.f.loseCommit.set(true);
            assertThrows(SQLException.class,()->h.operations.dispatch(h.owner,id,"test:generate",request));assertEquals("SENT",h.other().status(h.owner,id).state());
            assertEquals("SENT",h.other().claim(h.owner,id,"test:generate",request).state());assertEquals("PROVIDER_OPERATION_UNKNOWN",assertThrows(StorageFailure.class,()->h.other().dispatch(h.owner,id,"test:generate",request)).code);assertTrue(h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals")>0);
        }
    }
    @Test void fullyStagedResultAfterLostSealReplyIsRecoveredAcrossInstancesWithoutRepeatingTheEffect()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();java.util.concurrent.atomic.AtomicBoolean fail=new java.util.concurrent.atomic.AtomicBoolean(true);
            var original=h.f.authority;
            AtomicDatabase.Authority lost=new AtomicDatabase.Authority(){
                public ReplicaAuthority.Lease acquire()throws SQLException{return original.acquire();}
                public void unchanged(ReplicaAuthority.Lease lease)throws SQLException{original.unchanged(lease);}
                public void confirm(ReplicaAuthority.Lease lease,String gtid,String operation)throws SQLException{
                    original.confirm(lease,gtid,operation);
                    try{if(h.f.count("SELECT COUNT(*) FROM starhub_payload_manifests WHERE state='READY'")>0&&fail.getAndSet(false))throw new SQLException("Synthetic response lost after seal commit");}
                    catch(SQLException e){throw e;}catch(Exception e){throw new SQLException("Fixture query failed");}
                }
            };
            var operations=h.other(new AtomicDatabase(h.f.p,h.f::connection,lost));ObjectNode input=h.input(id);
            operations.claim(h.owner,id,"test:generate",input);var ticket=operations.dispatch(h.owner,id,"test:generate",input);try(var context=ticket.enter()){ProviderOperations.beforeExternal();}
            assertThrows(SQLException.class,()->ticket.complete(200,ModelGateway.JSON.createObjectNode().put("text","test-only-sensitive-output")));ticket.unknown();var status=h.other().status(h.owner,id);
            assertEquals("COMPLETED",status.state());assertEquals("test-only-sensitive-output",status.result().path("text").asText());assertEquals(0,h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));
            assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_provider_operations WHERE result_cipher IS NOT NULL"));
            try(Connection c=h.f.connection();ResultSet r=c.createStatement().executeQuery("SELECT content FROM starhub_payload_chunks")){while(r.next()){String raw=new String(r.getBytes(1),java.nio.charset.StandardCharsets.ISO_8859_1);assertFalse(raw.contains("sensitive-output"));assertFalse(raw.contains("not-a-real-api-key"));}}
        }
    }
    @Test void freshSingleQueryAndReplayOverrideCachedDualPayloadProof()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();ObjectNode input=h.input(id);var ticket=h.ticket(id,input);ticket.complete(200,ModelGateway.JSON.createObjectNode().put("text","test-only-result").put("durability","dual"));
            var old=h.f.authority.acquire();var single=new ReplicaAuthority.Lease(old.mysqlUuid(),"c".repeat(64),"explicit-test-single-view","2","single",old.boot(),old.membership());
            AtomicDatabase.Authority authority=new AtomicDatabase.Authority(){public ReplicaAuthority.Lease acquire(){return single;}public void unchanged(ReplicaAuthority.Lease permit){assertEquals(single,permit);}public void confirm(ReplicaAuthority.Lease permit,String gtid,String operation){assertEquals(single,permit);assertFalse(gtid.isBlank());assertEquals(id,operation);}};
            ProviderOperations recovered=h.other(new AtomicDatabase(h.f.p,h.f::connection,authority));var status=recovered.status(h.owner,id);assertEquals("single",status.durability());assertEquals("single",status.result().path("durability").asText());
            var replay=recovered.claim(h.owner,id,"test:generate",input);assertEquals("single",replay.durability());assertEquals("single",replay.result().path("durability").asText());assertEquals("COMPLETED",replay.state());
        }
    }
    @Test void missingChunkAfterUnknownAckNeverPublishesOrReleasesBudgetAndCannotRedispatch()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();var original=h.f.authority;var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
            AtomicDatabase.Authority interrupted=new AtomicDatabase.Authority(){
                public ReplicaAuthority.Lease acquire()throws SQLException{return original.acquire();}
                public void unchanged(ReplicaAuthority.Lease lease)throws SQLException{original.unchanged(lease);}
                public void confirm(ReplicaAuthority.Lease lease,String gtid,String operation)throws SQLException{
                    original.confirm(lease,gtid,operation);try{if(h.f.count("SELECT COUNT(*) FROM starhub_payload_chunks")==1&&fail.getAndSet(false))throw new SQLException("Synthetic first chunk response lost");}
                    catch(SQLException e){throw e;}catch(Exception e){throw new SQLException("Fixture query failed");}
                }
            };
            var operations=h.other(new AtomicDatabase(h.f.p,h.f::connection,interrupted));ObjectNode input=h.input(id);
            operations.claim(h.owner,id,"test:generate",input);var ticket=operations.dispatch(h.owner,id,"test:generate",input);
            assertThrows(SQLException.class,()->ticket.complete(200,ModelGateway.JSON.createObjectNode().put("text","synthetic-private-".repeat(350000))));ticket.unknown();
            var status=h.other().status(h.owner,id);assertEquals("UNKNOWN",status.state());assertNull(status.result());
            assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_payload_manifests WHERE state='STAGING'"));
            assertTrue(h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals")>0);
            assertEquals("UNKNOWN",h.other().claim(h.owner,id,"test:generate",input).state());assertThrows(StorageFailure.class,()->h.other().dispatch(h.owner,id,"test:generate",input));
        }
    }
    @Test void finalPublicationCommitWithLostAckResolvesWithoutNewChunksOrExternalEffect()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();var original=h.f.authority;var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
            AtomicDatabase.Authority lost=new AtomicDatabase.Authority(){
                public ReplicaAuthority.Lease acquire()throws SQLException{return original.acquire();}
                public void unchanged(ReplicaAuthority.Lease lease)throws SQLException{original.unchanged(lease);}
                public void confirm(ReplicaAuthority.Lease lease,String gtid,String operation)throws SQLException{
                    original.confirm(lease,gtid,operation);try{if(h.f.count("SELECT COUNT(*) FROM starhub_provider_operations WHERE state='COMPLETED'")==1&&fail.getAndSet(false))throw new SQLException("Synthetic published response lost");}
                    catch(SQLException e){throw e;}catch(Exception e){throw new SQLException("Fixture query failed");}
                }
            };
            var operations=h.other(new AtomicDatabase(h.f.p,h.f::connection,lost));ObjectNode input=h.input(id);operations.claim(h.owner,id,"test:generate",input);var ticket=operations.dispatch(h.owner,id,"test:generate",input);
            assertThrows(SQLException.class,()->ticket.complete(200,ModelGateway.JSON.createObjectNode().put("text","retained-final-value")));
            long chunks=h.f.count("SELECT COUNT(*) FROM starhub_payload_chunks");assertTrue(chunks>0);
            var status=h.other().status(h.owner,id);assertEquals("COMPLETED",status.state());assertEquals("retained-final-value",status.result().path("text").asText());
            assertEquals(0,h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));assertEquals(chunks,h.f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
            assertThrows(StorageFailure.class,()->h.other().dispatch(h.owner,id,"test:generate",input));
        }
    }
    @Test void completionIntentIsImmutableAndAnAliveHandlerCanResumeOnlyTheSameCipherPlan()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();var ticket=h.ticket(id,h.input(id));ObjectNode original=ModelGateway.JSON.createObjectNode().put("text","same-result-only");
            h.f.loseCommit.set(true);assertThrows(SQLException.class,()->ticket.complete(200,original));
            assertEquals("SENT",h.other().status(h.owner,id).state());assertTrue(h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals")>0);
            assertThrows(SQLException.class,()->ticket.complete(200,original.deepCopy().put("text","different-result")));
            assertEquals("COMPLETED",ticket.complete(200,original).state());assertEquals("same-result-only",h.other().status(h.owner,id).result().path("text").asText());
            assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_provider_operations WHERE result_cipher IS NOT NULL"));
        }
    }
    @Test void concurrentJvmClaimsCanProduceOnlyOneDispatchTicket()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();ObjectNode request=h.input(id);var other=h.other();AtomicInteger effects=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
            try{
                Callable<Void> first=()->{start.await();try{var claim=h.operations.claim(h.owner,id,"test:generate",request);if(claim.state().equals("CLAIMED")){var ticket=h.operations.dispatch(h.owner,id,"test:generate",request);try(var context=ticket.enter()){ProviderOperations.beforeExternal();effects.incrementAndGet();}ticket.complete(200,ModelGateway.JSON.createObjectNode().put("text","one"));}}catch(StorageFailure uncertain){assertEquals("PROVIDER_OPERATION_UNKNOWN",uncertain.code);}return null;};
                Callable<Void> second=()->{start.await();try{var claim=other.claim(h.owner,id,"test:generate",request);if(claim.state().equals("CLAIMED")){var ticket=other.dispatch(h.owner,id,"test:generate",request);try(var context=ticket.enter()){ProviderOperations.beforeExternal();effects.incrementAndGet();}ticket.complete(200,ModelGateway.JSON.createObjectNode().put("text","one"));}}catch(StorageFailure uncertain){assertEquals("PROVIDER_OPERATION_UNKNOWN",uncertain.code);}return null;};
                Future<Void> a=pool.submit(first),b=pool.submit(second);start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(1,effects.get());assertEquals("COMPLETED",h.operations.status(h.owner,id).state());
            }finally{pool.shutdownNow();}
        }
    }
    @Test void payloadOwnerAndChangedPermitCannotBypassTheOriginalClaim()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();ObjectNode request=h.input(id);var ticket=h.ticket(id,request);
            assertEquals("PROVIDER_OPERATION_CONFLICT",assertThrows(StorageFailure.class,()->h.operations.claim(h.owner,id,"test:generate",request.deepCopy().put("apiKey","different-test-only-key"))).code);
            assertEquals(404,assertThrows(StorageFailure.class,()->h.operations.status(new ProviderOperations.Owner("another-test-owner",c->{}),id)).status);
            h.f.changePermit.set(true);try(var context=ticket.enter()){assertThrows(IOException.class,ProviderOperations::beforeExternal);}assertFalse(ticket.attempted());h.f.changePermit.set(false);ticket.unknown();
            assertEquals("UNKNOWN",h.operations.status(h.owner,id).state());assertThrows(StorageFailure.class,()->h.operations.dispatch(h.owner,id,"test:generate",request));assertTrue(h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals")>0);
        }
    }
    @Test void capacityRefusalDoesNotCommitAClaimOrSendAnything()throws Exception {
        try(Harness h=new Harness()){
            h.f.availableBytes.set(StorageCluster.FLOOR+1);String id=UUID.randomUUID().toString();assertEquals(507,assertThrows(StorageFailure.class,()->h.operations.claim(h.owner,id,"test:generate",h.input(id))).status);
            assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_provider_operations"));assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_provider_capacity"));assertEquals(0,h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));assertEquals("ABSENT",h.operations.status(h.owner,id).state());
        }
    }
    @Test void exactThirtyTwoMiBResultRemainsReadableAcrossInstancesWithItsCompleteOriginalText()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();var skeleton=ModelGateway.JSON.createObjectNode().put("text","").put("operationId",id).put("operationState","COMPLETED");int length=ProviderOperations.RESULT_LIMIT-ModelGateway.JSON.writeValueAsBytes(skeleton).length;
            int publicLimit=ModelGateway.JSON.getFactory().streamReadConstraints().getMaxStringLength();assertTrue(publicLimit<ProviderOperations.RESULT_LIMIT);
            String text="z".repeat(length);var ticket=h.ticket(id,h.input(id));var result=ticket.complete(200,ModelGateway.JSON.createObjectNode().put("text",text));assertEquals("COMPLETED",result.state());assertEquals(length,result.result().path("text").textValue().length());
            var recovered=h.other().status(h.owner,id);assertEquals("COMPLETED",recovered.state());assertEquals(text,recovered.result().path("text").textValue());assertEquals(0,h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));assertTrue(h.f.count("SELECT COUNT(*) FROM starhub_payload_chunks")>16);assertEquals(publicLimit,ModelGateway.JSON.getFactory().streamReadConstraints().getMaxStringLength());
        }
    }
    @Test void oversizedResultIsAnExplicitNonReplayableTerminalRecordAndKeepsSingleImageSupport()throws Exception {
        try(Harness h=new Harness()){
            assertTrue(ProviderOperations.RESULT_LIMIT>((GeneratedImages.MAX_IMAGE+2L)/3)*4+65536);String id=UUID.randomUUID().toString();var ticket=h.ticket(id,h.input(id));
            var done=ticket.complete(200,ModelGateway.JSON.createObjectNode().put("text","x".repeat(ProviderOperations.RESULT_LIMIT+1)));assertEquals("COMPLETED_NO_RESULT",done.state());assertEquals("PROVIDER_RESULT_NOT_RETAINED",h.operations.status(h.owner,id).result().path("code").asText());assertEquals(0,h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));assertThrows(StorageFailure.class,()->h.operations.dispatch(h.owner,id,"test:generate",h.input(id)));
        }
    }
    @Test void actualCatalogueAndQuotaCountExistingCommittedAndReservedBytes()throws Exception {
        try(Harness h=new Harness()){
            h.f.files.upload(h.f.ingress(),1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("prior.txt","text/plain",new byte[]{1,2})));
            var metadata=ModelGateway.JSON.createArrayNode();metadata.addObject().put("name","pending.txt").put("mime","text/plain").put("size",3).put("sha256",Base64.getEncoder().encodeToString(FileStorage.hash(new byte[]{3,4,5})));
            h.f.files.reserve(1,UUID.randomUUID().toString(),metadata,2);var quota=AtomicStorageValidation.quota(h.f.files,1);assertEquals(2,quota.used());assertEquals(3,quota.reserved());assertEquals(WorkspaceServlet.QUOTA-5,quota.remaining());
        }
    }
}
