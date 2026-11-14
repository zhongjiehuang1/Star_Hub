package com.starhub;

import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real local storage SQL and actor queries; explicit test authority/capacity adapters. */
class WorkspaceSegmentedDispatchTest {
    static final class Harness implements AutoCloseable {
        final AtomicTransactionsTest.Fixture f=new AtomicTransactionsTest.Fixture();
        final Path config=Files.createTempFile("starhub-segmented-test-",".properties");
        final String previous=System.getProperty("starhub.account.config");
        final FileStorage previousActive=FileStorage.active,dispatcher=mock(FileStorage.class);
        final WorkspaceServlet servlet=new WorkspaceServlet();
        final HttpSession session=mock(HttpSession.class);final Map<String,Object> attributes=new HashMap<>();
        Harness()throws Exception {
            try(OutputStream out=Files.newOutputStream(config)){f.p.store(out,"Synthetic local SQL fixture only");}System.setProperty("starhub.account.config",config.toString());
            attributes.put("starhub.account.id",1L);attributes.put("starhub.account.email","user@example.test");attributes.put("starhub.account.generation",f.generation);attributes.put("starhub.account.hash",f.accountHash);
            when(session.getAttribute(anyString())).thenAnswer(i->attributes.get(i.getArgument(0)));
            FileStorage.active=dispatcher;
            when(dispatcher.uploadReserved(any(),anyLong(),anyString(),anyList())).thenAnswer(i->f.files.complete(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3)));
            when(dispatcher.cancelUpload(anyLong(),anyString())).thenAnswer(i->f.files.cancel(i.getArgument(0),i.getArgument(1)));
            when(dispatcher.delete(anyLong(),anyString(),anyString())).thenAnswer(i->f.files.delete(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
        }
        HttpServletRequest request(ObjectNode body)throws Exception {
            HttpServletRequest req=f.ingress();when(req.getSession(anyBoolean())).thenReturn(session);when(req.getContentType()).thenReturn("application/json");when(req.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(req.getHeader("X-Starhub-Client")).thenReturn("web");
            ByteArrayInputStream bytes=new ByteArrayInputStream(ModelGateway.JSON.writeValueAsBytes(body));when(req.getInputStream()).thenReturn(new ServletInputStream(){public int read(){return bytes.read();}public boolean isFinished(){return bytes.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}});return req;
        }
        ObjectNode input(String action,String operation){return ModelGateway.JSON.createObjectNode().put("action",action).put("operationId",operation).put("expectedEmail","user@example.test");}
        AtomicTransactionsTest.Capture dispatch(ObjectNode body,long user)throws Exception {
            var response=new AtomicTransactionsTest.Capture();assertTrue(servlet.segmentedFileAction(request(body),response.response,user,body,body.path("action").asText()));return response;
        }
        void reserve(String op,byte[] content)throws Exception {ArrayNode metadata=ModelGateway.JSON.createArrayNode();metadata.add(new FileStorage.Upload("fixture.txt","text/plain",content).metadata());f.files.reserve(1,op,metadata,1);}
        public void close()throws Exception {FileStorage.active=previousActive;if(previous==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",previous);Files.deleteIfExists(config);f.close();}
    }
    @Test void uploadAndReplayKeepOneFileAndQuotaWithoutAnUnusedUnscopedConnection()throws Exception {
        try(Harness h=new Harness()){
            byte[] bytes=new byte[8192];new Random(81).nextBytes(bytes);String op=UUID.randomUUID().toString();h.reserve(op,bytes);
            ObjectNode request=h.input("upload",op).put("name","fixture.txt").put("mime","text/plain").put("data",Base64.getEncoder().encodeToString(bytes));
            var first=h.dispatch(request,1);assertEquals(200,first.status.get());String id=ModelGateway.JSON.readTree(first.bytes.toByteArray()).path("id").asText();
            var replay=h.dispatch(request,1);assertEquals(200,replay.status.get());assertEquals(id,ModelGateway.JSON.readTree(replay.bytes.toByteArray()).path("id").asText());
            assertEquals(1,h.f.count("SELECT COUNT(*) FROM library_files"));assertEquals(bytes.length,h.f.count("SELECT used_bytes FROM file_storage_users"));assertEquals(0,h.f.count("SELECT reserved_bytes FROM file_storage_users"));
            byte[] changed=bytes.clone();changed[0]^=1;assertEquals(409,assertThrows(StorageFailure.class,()->h.dispatch(request.deepCopy().put("data",Base64.getEncoder().encodeToString(changed)),1)).status);
            assertArrayEquals(bytes,Base64.getDecoder().decode(h.f.files.read(1,id).path("data").asText()));
        }
    }
    @Test void cancellationIsIdempotentAndLateUploadCannotRecreateCancelledBytes()throws Exception {
        try(Harness h=new Harness()){
            byte[] bytes={1,2,3};String op=UUID.randomUUID().toString();h.reserve(op,bytes);
            for(int retry=0;retry<2;retry++){var reply=h.dispatch(h.input("cancelUpload",op),1);assertEquals(200,reply.status.get());assertEquals("CANCELLED",ModelGateway.JSON.readTree(reply.bytes.toByteArray()).path("state").asText());}
            assertEquals(0,h.f.count("SELECT reserved_bytes FROM file_storage_users"));assertEquals(0,h.f.count("SELECT COUNT(*) FROM library_files"));
            assertEquals(409,assertThrows(StorageFailure.class,()->h.dispatch(h.input("upload",op).put("name","fixture.txt").put("mime","text/plain").put("data",Base64.getEncoder().encodeToString(bytes)),1)).status);
        }
    }
    @Test void crossOwnerCancellationAndDeletionDoNotReleaseAnotherOwnersQuota()throws Exception {
        try(Harness h=new Harness()){
            String generation=UUID.randomUUID().toString();try(Connection c=h.f.connection();PreparedStatement q=c.prepareStatement("INSERT INTO users(id,active,generation,email,password_hash) VALUES(2,TRUE,?,'other@example.test',?)")){q.setString(1,generation);q.setBytes(2,new byte[]{9});q.executeUpdate();}
            String op=UUID.randomUUID().toString();h.reserve(op,new byte[]{1});
            assertEquals(404,assertThrows(StorageFailure.class,()->h.dispatch(h.input("cancelUpload",op),2)).status);assertEquals(1,h.f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));
            String file=h.f.files.complete(h.request(h.input("upload",op)),1,op,List.of(new FileStorage.Upload("fixture.txt","text/plain",new byte[]{1}))).path("id").asText();
            // The existing engine's owner-binding failure is SQLException,
            // translated to a closed 503 by doPost; do not weaken or relabel it.
            assertThrows(SQLException.class,()->h.dispatch(h.input("deleteFile",UUID.randomUUID().toString()).put("id",file),2));
            assertEquals(1,h.f.count("SELECT COUNT(*) FROM library_files WHERE user_id=1"));assertEquals(1,h.f.count("SELECT used_bytes FROM file_storage_users WHERE user_id=1"));
        }
    }
    @Test void confirmedDeletionReplayReleasesQuotaOnceAndChangedTargetConflicts()throws Exception {
        try(Harness h=new Harness()){
            byte[] bytes={5,6,7};String upload=UUID.randomUUID().toString();h.reserve(upload,bytes);
            String id=h.f.files.complete(h.request(h.input("upload",upload)),1,upload,List.of(new FileStorage.Upload("fixture.txt","text/plain",bytes))).path("id").asText();
            h.f.files.trash(1,UUID.randomUUID().toString(),id,false);String operation=UUID.randomUUID().toString();ObjectNode deletion=h.input("deleteFile",operation).put("id",id);
            for(int attempt=0;attempt<2;attempt++){var result=h.dispatch(deletion,1);assertEquals(200,result.status.get());assertEquals("COMMITTED",ModelGateway.JSON.readTree(result.bytes.toByteArray()).path("state").asText());}
            assertEquals(0,h.f.count("SELECT used_bytes FROM file_storage_users"));assertEquals(0,h.f.count("SELECT reserved_bytes FROM file_storage_users"));assertEquals(0,h.f.count("SELECT COUNT(*) FROM library_files"));assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
            assertEquals(409,assertThrows(StorageFailure.class,()->h.dispatch(deletion.deepCopy().put("id",UUID.randomUUID().toString()),1)).status);
        }
    }
    @Test void invalidFileParametersFailBeforeAnyStorageDispatch()throws Exception {
        try(Harness h=new Harness()){
            for(ObjectNode request:List.of(h.input("upload",UUID.randomUUID().toString()).put("name","../bad.txt").put("mime","text/plain").put("data","AQ=="),h.input("upload",UUID.randomUUID().toString()).put("name","ok.txt").put("mime","text/plain").put("data","not base64"))){assertEquals(400,h.dispatch(request,1).status.get());}
            verifyNoInteractions(h.dispatcher);
        }
    }
    @Test void fullServletStillRejectsClosedAccountsChangedOwnersAndBadOriginsBeforeFileDispatch()throws Exception {
        try(Harness h=new Harness()){
            ObjectNode body=h.input("cancelUpload",UUID.randomUUID().toString());
            try(Connection c=h.f.connection()){c.createStatement().executeUpdate("UPDATE users SET active=FALSE WHERE id=1");}
            try(AtomicDatabase.Scope ignored=h.f.db.begin(UUID.randomUUID().toString())){var response=new AtomicTransactionsTest.Capture();h.servlet.doPost(h.request(body),response.response);assertEquals(401,response.status.get());}
            try(Connection c=h.f.connection()){c.createStatement().executeUpdate("UPDATE users SET active=TRUE WHERE id=1");}
            try(AtomicDatabase.Scope ignored=h.f.db.begin(UUID.randomUUID().toString())){var response=new AtomicTransactionsTest.Capture();h.servlet.doPost(h.request(body.deepCopy().put("expectedEmail","other@example.test")),response.response);assertEquals(409,response.status.get());}
            var request=h.request(body);when(request.getHeader("Origin")).thenReturn("https://untrusted.example.test");var response=new AtomicTransactionsTest.Capture();h.servlet.doPost(request,response.response);assertEquals(403,response.status.get());verifyNoInteractions(h.dispatcher);
        }
    }
}
