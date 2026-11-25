package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.sql.*;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Two distinct coordinators use real local SQL; authority/capacity remain explicit adapters. */
class AtomicGuestStorageTest {
    static final String TAB="a".repeat(32),TX="b".repeat(32),COOKIE="random-test-only-session-cookie";
    static ObjectNode input(String action,String tx){return ModelGateway.JSON.createObjectNode().put("tab",TAB).put("action",action).put("tx",tx);}
    static ObjectNode snapshot(String tx,long version,String content){ObjectNode request=input("snapshot",tx).put("version",version);request.putObject("values").putObject("history").put("value",content);return request;}
    @Test void lostInitialIdentityReplyCanBeRecoveredOnAnotherJvmWithTheSameRoutedCookie()throws Exception {
        try(var h=new GuestSessionServletTest.Harness()){
            h.f.loseCommit.set(true);assertThrows(SQLException.class,()->h.store.handle(COOKIE+".node1",null,input("get",""),1000));
            var read=h.other().handle(COOKIE+".node2",null,input("get",""),1100);assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_guest_sessions"));
            assertEquals(read.identity(),h.store.handle(COOKIE,read.identity(),input("get",""),1200).identity());
            try(var bytes=new ByteArrayOutputStream();var serialized=new ObjectOutputStream(bytes)){serialized.writeObject(read.identity());assertTrue(bytes.size()>0);}
        }
    }
    @Test void unknownChunkCommitReplaysOnceAndCannotChangeItsContent()throws Exception {
        try(var h=new GuestSessionServletTest.Harness()){
            String guest=h.store.handle(COOKIE,null,input("begin",TX).put("field","keys").put("version",1),1000).identity();
            ObjectNode chunk=input("chunk",TX).put("field","keys").put("index",0).put("data","{\"provider\":\"test-only-key\"}");h.f.loseCommit.set(true);
            assertThrows(SQLException.class,()->h.store.handle(COOKIE,guest,chunk,1100));h.other().handle(COOKIE,guest,chunk,1200);
            assertEquals("COMMITTED",h.other().handle(COOKIE,guest,input("status","").put("operationId",AtomicGuestStorage.operationId(chunk)),1250).body().path("state").asText());
            assertEquals(1,h.f.count("SELECT next_chunk FROM starhub_guest_streams"));assertEquals(chunk.path("data").asText().length(),h.f.count("SELECT used_units FROM starhub_guest_budget"));
            assertEquals("GUEST_OPERATION_CONFLICT",assertThrows(StorageFailure.class,()->h.store.handle(COOKIE,guest,chunk.deepCopy().put("data","changed"),1300)).code);
            assertFalse(h.other().handle(COOKIE,guest,input("get",""),1400).body().has("keys"));h.other().handle(COOKIE,guest,input("commit",TX).put("field","keys"),1500);
            assertEquals("test-only-key",h.store.handle(COOKIE,guest,input("get",""),1600).body().path("keys").path("provider").asText());assertEquals(3,h.f.count("SELECT COUNT(*) FROM starhub_guest_operations"));
        }
    }
    @Test void concurrentSameVersionOnSeparateCoordinatorsHasOneWinnerAndNoSilentOverwrite()throws Exception {
        try(var h=new GuestSessionServletTest.Harness()){
            String guest=h.store.handle(COOKIE,null,input("get",""),1000).identity();var other=h.other();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
            try {
                var a=pool.submit(()->{start.await();try{h.store.handle(COOKIE,guest,snapshot("c".repeat(32),1,"first"),1100);return 200;}catch(StorageFailure conflict){return conflict.status;}});
                var b=pool.submit(()->{start.await();try{other.handle(COOKIE,guest,snapshot("d".repeat(32),1,"second"),1100);return 200;}catch(StorageFailure conflict){return conflict.status;}});
                start.countDown();assertEquals(Set.of(200,409),Set.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)));assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_guest_values"));assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_guest_operations"));
                assertEquals(h.f.count("SELECT SUM(size_units) FROM starhub_guest_values"),h.f.count("SELECT used_units FROM starhub_guest_budget"));
            }finally{pool.shutdownNow();}
        }
    }
    @Test void aDelayedOlderBeginCannotEraseAnAlreadyReservedHigherVersion()throws Exception {
        try(var h=new GuestSessionServletTest.Harness()){
            String guest=h.store.handle(COOKIE,null,input("begin",TX).put("field","history").put("version",2),1000).identity();
            h.store.handle(COOKIE,guest,input("chunk",TX).put("field","history").put("index",0).put("data","{}"),1100);
            assertEquals("GUEST_VERSION_CONFLICT",assertThrows(StorageFailure.class,()->h.other().handle(COOKIE,guest,input("begin","c".repeat(32)).put("field","history").put("version",1),1200)).code);
            assertEquals(2,h.f.count("SELECT used_units FROM starhub_guest_budget"));assertEquals(2,h.f.count("SELECT version FROM starhub_guest_streams"));h.other().handle(COOKIE,guest,input("commit",TX).put("field","history"),1300);
        }
    }
    @Test void sharedBudgetRefusalRollsBackFieldsReceiptAndBothCounters()throws Exception {
        try(var h=new GuestSessionServletTest.Harness()){
            String guest=h.store.handle(COOKIE,null,input("get",""),1000).identity();
            // An explicit synthetic boundary counter avoids allocating 128 MB in a unit test.
            try(Connection c=h.f.connection()){c.createStatement().executeUpdate("UPDATE starhub_guest_budget SET used_units="+(GuestSessionServlet.MAX_TOTAL-1));}
            assertEquals(413,assertThrows(StorageFailure.class,()->h.other().handle(COOKIE,guest,snapshot(TX,1,"value"),1100)).status);
            assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_guest_values"));assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_guest_operations"));assertEquals(0,h.f.count("SELECT used_units FROM starhub_guest_sessions"));assertEquals(GuestSessionServlet.MAX_TOTAL-1,h.f.count("SELECT used_units FROM starhub_guest_budget"));
        }
    }
    @Test void encryptionPreservesPackedLoneSurrogatesAndDetectsCipherTampering()throws Exception {
        try(var h=new GuestSessionServletTest.Harness()){
            String packed="binary:"+(char)0xd800+":"+(char)0xdc00+":"+(char)0xfffd;
            String guest=h.store.handle(COOKIE,null,snapshot(TX,1,packed),1000).identity();ObjectNode output=h.other().handle(COOKIE,guest,input("get",""),1100).body();assertEquals(packed,output.path("history").path("value").asText());
            assertEquals(packed,ModelGateway.JSON.readTree(ModelGateway.JSON.writeValueAsBytes(output)).path("history").path("value").asText());
            try(Connection c=h.f.connection();Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT content FROM starhub_payload_chunks")){r.next();byte[] bytes=r.getBytes(1);assertFalse(new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1).contains("binary"));bytes[bytes.length-1]^=1;try(PreparedStatement corrupt=c.prepareStatement("UPDATE starhub_payload_chunks SET content=?")){corrupt.setBytes(1,bytes);corrupt.executeUpdate();}}
            assertThrows(SQLException.class,()->h.store.handle(COOKIE,guest,input("get",""),1200));
        }
    }
    @Test void expirationRejectsAccessAndExplicitRenewRetainsReceiptsAndPendingBytes()throws Exception {
        try(var h=new GuestSessionServletTest.Harness()){
            String guest=h.store.handle(COOKIE,null,input("begin",TX).put("field","history").put("version",1),1000).identity();h.store.handle(COOKIE,guest,input("chunk",TX).put("field","history").put("index",0).put("data","{}"),1100);
            assertEquals("GUEST_SESSION_EXPIRED",assertThrows(StorageFailure.class,()->h.other().handle(COOKIE,guest,input("get",""),602000)).code);
            assertEquals(2,h.f.count("SELECT used_units FROM starhub_guest_budget"));assertEquals(2,h.f.count("SELECT COUNT(*) FROM starhub_guest_operations"));
            ObjectNode renew=input("renew","e".repeat(32));h.other().handle(COOKIE,guest,renew,602001);h.store.handle(COOKIE,guest,renew,602002);assertEquals(3,h.f.count("SELECT COUNT(*) FROM starhub_guest_operations"));
            h.other().handle(COOKIE,guest,input("commit",TX).put("field","history"),602003);assertTrue(h.store.handle(COOKIE,guest,input("get",""),602004).body().path("history").isObject());assertEquals(2,h.f.count("SELECT used_units FROM starhub_guest_budget"));
        }
    }
    @Test void runtimeProbeUsesRealCriticalTablesAndStableAuthorityWithoutWritingTheCertificationGate()throws Exception {
        try(var h=new GuestSessionServletTest.Harness()){
            String identity=UUID.randomUUID().toString();try(Connection c=h.f.connection();Statement q=c.createStatement()){q.execute("CREATE TABLE file_storage_coordinator(id INT PRIMARY KEY,store_identity CHAR(36))");q.execute("INSERT INTO file_storage_coordinator VALUES(1,'"+identity+"')");}
            long before=h.f.count("SELECT revision FROM file_storage_atomic_gate");h.f.db.runtimeProbe(identity);assertEquals(before,h.f.count("SELECT revision FROM file_storage_atomic_gate"));
            assertThrows(SQLException.class,()->h.f.db.runtimeProbe(UUID.randomUUID().toString()));h.f.changePermit.set(true);assertThrows(SQLException.class,()->h.f.db.runtimeProbe(identity));h.f.changePermit.set(false);
            try(Connection c=h.f.connection()){c.createStatement().execute("DROP TABLE starhub_guest_staging");}assertThrows(SQLException.class,()->h.f.db.runtimeProbe(identity));assertEquals(before,h.f.count("SELECT revision FROM file_storage_atomic_gate"));
        }
    }
    @Test void runtimeReadinessRecoversWhileCapacityObserverIsUnavailableWithoutCallingIt()throws Exception {
        try(var h=new GuestSessionServletTest.Harness()){
            String identity=UUID.randomUUID().toString();java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("starhub-readiness-test-");
            try{
                java.nio.file.Files.writeString(directory.resolve("identity"),identity);h.f.p.setProperty("storage.objectStore",directory.toString());h.f.p.setProperty("storage.node1.probe.url","http://127.0.0.1:1/unavailable");
                try(Connection c=h.f.connection();Statement q=c.createStatement()){q.execute("CREATE TABLE file_storage_coordinator(id INT PRIMARY KEY,store_identity CHAR(36))");q.execute("INSERT INTO file_storage_coordinator VALUES(1,'"+identity+"')");}
                StorageCluster unavailable=new StorageCluster(h.f.p);assertThrows(StorageFailure.class,unavailable::probes);assertTrue(FileStorage.atomicRuntimeReady(h.f.files,unavailable));h.f.changePermit.set(true);assertFalse(FileStorage.atomicRuntimeReady(h.f.files,unavailable));
            }finally{java.nio.file.Files.deleteIfExists(directory.resolve("identity"));java.nio.file.Files.deleteIfExists(directory);}
        }
    }
    @Test void physicalCapacityRefusalCannotPublishIdentityOrChargeTheGlobalBudget()throws Exception {
        try(var h=new GuestSessionServletTest.Harness()){
            var denied=new AtomicGuestStorage(h.f.db,h.f.p,(c,bytes)->{throw new StorageFailure(507,"INSUFFICIENT_STORAGE","Explicit test refusal");});
            assertEquals(507,assertThrows(StorageFailure.class,()->denied.handle(COOKIE,null,snapshot(TX,1,"x"),1000)).status);assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_guest_sessions"));assertEquals(0,h.f.count("SELECT used_units FROM starhub_guest_budget"));
        }
    }
}
