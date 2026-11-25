package com.starhub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real local SQL/chunks plus the real atomic HTTP wrapper; capacity/authority are explicit fixture adapters. */
class AtomicStorageRefusalTest {
    private HttpServletRequest request(String operation,String action)throws Exception {
        var r=mock(HttpServletRequest.class);when(r.getMethod()).thenReturn("POST");when(r.getServletPath()).thenReturn("/api/workspace");when(r.getContentType()).thenReturn("application/json");when(r.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(r.getHeader("X-Starhub-Client")).thenReturn("web");
        byte[] body=ModelGateway.JSON.writeValueAsBytes(ModelGateway.JSON.createObjectNode().put("action",action).put("operationId",operation).put("expectedEmail","user@example.test"));var bytes=new ByteArrayInputStream(body);
        when(r.getInputStream()).thenReturn(new ServletInputStream(){public int read(){return bytes.read();}public boolean isFinished(){return bytes.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}});return r;
    }
    private void failure(ServletResponse response,StorageFailure failure)throws IOException {((HttpServletResponse)response).setStatus(failure.status);response.getOutputStream().write(ModelGateway.JSON.writeValueAsBytes(ModelGateway.JSON.createObjectNode().put("code",failure.code).put("error",failure.getMessage())));}
    @Test void refusalAfterFirstCommittedChunkKeepsTheOriginalIdAndCanFinishAcrossInstances()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            String op=UUID.randomUUID().toString();byte[] bytes=new byte[4*1024*1024];new Random(37).nextBytes(bytes);var file=new FileStorage.Upload("partial.txt","text/plain",bytes);var metadata=ModelGateway.JSON.createArrayNode();var item=file.metadata();item.remove("id");metadata.add(item);var reserved=f.files.reserve(1,op,metadata,1);String id=reserved.path("id").asText();
            var shrink=new AtomicBoolean(true);var base=f.authority;
            AtomicDatabase.Authority authority=new AtomicDatabase.Authority(){public ReplicaAuthority.Lease acquire()throws SQLException{return base.acquire();}public void unchanged(ReplicaAuthority.Lease lease)throws SQLException{base.unchanged(lease);}public void confirm(ReplicaAuthority.Lease lease,String gtid,String operation)throws SQLException{base.confirm(lease,gtid,operation);try{if(f.count("SELECT COUNT(*) FROM starhub_payload_chunks")==1&&shrink.getAndSet(false))f.availableBytes.set(StorageCluster.FLOOR+1);}catch(Exception failed){throw new SQLException("Fixture capacity observation failed");}}};
            var storage=new AtomicFileStorage(new AtomicDatabase(f.p,f::connection,authority),f.cluster());FilterChain work=(req,res)->{try{ObjectNode result=storage.complete((HttpServletRequest)req,1,op,List.of(file));res.getOutputStream().write(ModelGateway.JSON.writeValueAsBytes(result));}catch(StorageFailure refusal){failure(res,refusal);}catch(Exception failed){throw new ServletException(failed);}};
            var refused=new AtomicTransactionsTest.Capture();AtomicHttp.run(request(op,"upload"),refused.response,work,f.db,f.p,()->{},f.files::transientCapacity);assertEquals(507,refused.status.get());var body=ModelGateway.JSON.readTree(refused.bytes.toByteArray());assertEquals("INSUFFICIENT_STORAGE",body.path("code").asText());assertEquals(op,body.path("operationId").asText());assertEquals("UNKNOWN",body.path("operationState").asText());assertFalse(body.has("durability"));verify(refused.response,never()).setHeader(eq("X-Four-Project-Durability"),anyString());
            assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertEquals(0,f.count("SELECT COUNT(*) FROM library_files"));assertEquals(bytes.length,f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));assertEquals("RESERVED",f.files.status(1,op).path("state").asText());
            f.availableBytes.set(50_000_000_000L);var resumed=new AtomicTransactionsTest.Capture();AtomicHttp.run(request(op,"upload"),resumed.response,work,f.db,f.p,()->{},f.files::transientCapacity);assertEquals(200,resumed.status.get());assertEquals(id,ModelGateway.JSON.readTree(resumed.bytes.toByteArray()).path("id").asText());assertArrayEquals(bytes,f.files.readBinary(1,id).bytes());assertEquals(1,f.count("SELECT COUNT(*) FROM library_files"));assertEquals(0,f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));
        }
    }
    @Test void initialReservationRefusalCarriesItsIdWithoutCreatingOrAcknowledgingAReservation()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            String op=UUID.randomUUID().toString();f.availableBytes.set(StorageCluster.FLOOR+1);var capture=new AtomicTransactionsTest.Capture();
            AtomicHttp.run(request(op,"reserveUpload"),capture.response,(req,res)->{try{var metadata=ModelGateway.JSON.createArrayNode();metadata.addObject().put("name","a.txt").put("mime","text/plain").put("size",1).put("sha256",Base64.getEncoder().encodeToString(FileStorage.hash(new byte[]{1})));f.files.reserve(1,op,metadata,1);}catch(StorageFailure refused){failure(res,refused);}catch(Exception failed){throw new ServletException(failed);}},f.db,f.p,()->{},f.files::transientCapacity);
            assertEquals(507,capture.status.get());var body=ModelGateway.JSON.readTree(capture.bytes.toByteArray());assertEquals(op,body.path("operationId").asText());assertEquals("UNKNOWN",body.path("operationState").asText());assertEquals(0,f.count("SELECT COUNT(*) FROM file_storage_operations"));assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_reserved_capacity"));verify(capture.response,never()).setHeader(eq("X-Four-Project-Durability"),anyString());
        }
    }
    @Test void unexpectedFiveHundredsRemainUnknownAndCannotMasqueradeAsAStorageRefusal()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            String op=UUID.randomUUID().toString();for(String text:List.of("{\"code\":\"CLUSTER_UNAVAILABLE\"}","not-json")){
                var capture=new AtomicTransactionsTest.Capture();AtomicHttp.run(request(op,"upload"),capture.response,(req,res)->{((HttpServletResponse)res).setStatus(507);res.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));},f.db,f.p,()->{},f.files::transientCapacity);assertEquals(503,capture.status.get());var body=ModelGateway.JSON.readTree(capture.bytes.toByteArray());assertEquals(op,body.path("operationId").asText());assertEquals("UNKNOWN",body.path("operationState").asText());
            }
        }
    }
    @Test void cleanupRefusalKeepsAllBytesAndReservationsUntilTheOriginalIdCanSafelyResume()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            byte[] bytes=new byte[2*1024*1024];new Random(25).nextBytes(bytes);var upload=new FileStorage.Upload("cleanup.txt","text/plain",bytes);var metadata=ModelGateway.JSON.createArrayNode();var item=upload.metadata();item.remove("id");metadata.add(item);
            String pending=UUID.randomUUID().toString();f.files.reserve(1,pending,metadata,1);f.availableBytes.set(StorageCluster.FLOOR+1);assertEquals(507,assertThrows(StorageFailure.class,()->f.files.cancel(1,pending)).status);assertEquals("RESERVED",f.files.status(1,pending).path("state").asText());assertEquals(bytes.length,f.count("SELECT reserved_bytes FROM file_storage_users"));f.availableBytes.set(50_000_000_000L);assertEquals("CANCELLED",f.files.cancel(1,pending).path("state").asText());
            String committed=UUID.randomUUID().toString();String file=f.files.upload(f.ingress(),1,committed,List.of(upload)).path("id").asText();f.files.trash(1,UUID.randomUUID().toString(),file,false);String deletion=UUID.randomUUID().toString();long chunks=f.count("SELECT COUNT(*) FROM starhub_payload_chunks"),used=f.count("SELECT used_bytes FROM file_storage_users");f.availableBytes.set(StorageCluster.FLOOR+1);assertEquals(507,assertThrows(StorageFailure.class,()->f.files.delete(1,deletion,file)).status);assertEquals(chunks,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertEquals(used,f.count("SELECT used_bytes FROM file_storage_users"));assertEquals(1,f.count("SELECT COUNT(*) FROM library_files"));assertEquals(0,f.count("SELECT COUNT(*) FROM file_storage_operations WHERE kind='DELETE'"));f.availableBytes.set(50_000_000_000L);assertEquals("COMMITTED",f.files.delete(1,deletion,file).path("state").asText());assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertEquals(0,f.count("SELECT used_bytes FROM file_storage_users"));
        }
    }
}
