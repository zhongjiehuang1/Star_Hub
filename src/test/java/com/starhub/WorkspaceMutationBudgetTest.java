package com.starhub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H2 row changes/receipts; physical counters and authority are explicit fixtures. */
class WorkspaceMutationBudgetTest {
    private static final class Config implements AutoCloseable {
        final Path path=Files.createTempFile("starhub-budget-test-only-",".properties");final String prior=System.getProperty("starhub.account.config");
        Config(AtomicTransactionsTest.Fixture f)throws Exception{try(OutputStream out=Files.newOutputStream(path)){f.p.store(out,"Synthetic fixture only");}System.setProperty("starhub.account.config",path.toString());}
        public void close()throws Exception{if(prior==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",prior);Files.delete(path);}
    }
    private ObjectNode body(String op,String action){return ModelGateway.JSON.createObjectNode().put("action",action).put("operationId",op).put("expectedEmail","user@example.test").put("id","chat").put("title","new").set("data",ModelGateway.JSON.createObjectNode().put("text","你好"));}
    private HttpServletRequest request(AtomicTransactionsTest.Fixture f,ObjectNode body)throws Exception {
        var req=mock(HttpServletRequest.class);when(req.getMethod()).thenReturn("POST");when(req.getServletPath()).thenReturn("/api/workspace");when(req.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(req.getHeader("X-Starhub-Client")).thenReturn("web");when(req.getContentType()).thenReturn("application/json");
        var session=mock(HttpSession.class);Map<String,Object> attrs=Map.of("starhub.account.id",1L,"starhub.account.email","user@example.test","starhub.account.generation",f.generation,"starhub.account.hash",f.accountHash);when(session.getAttribute(anyString())).thenAnswer(i->attrs.get(i.getArgument(0)));when(session.getAttributeNames()).thenAnswer(i->Collections.enumeration(attrs.keySet()));when(req.getSession(anyBoolean())).thenReturn(session);
        var in=new ByteArrayInputStream(ModelGateway.JSON.writeValueAsBytes(body));when(req.getInputStream()).thenReturn(new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener ignored){}});return req;
    }
    private void old(AtomicTransactionsTest.Fixture f,String title,String payload)throws Exception {try(Connection c=f.connection();PreparedStatement p=c.prepareStatement("INSERT INTO conversations VALUES(1,'chat',?,?,1)")){p.setString(1,title);p.setString(2,payload);p.executeUpdate();}}
    @Test void fullOldAndNewUtf8ImagesAndReceiptAreBudgetedBeforeTheHandler()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture();var cfg=new Config(f)){
            String prior="{\"text\":\"旧记录\"}";old(f,"旧",prior);var body=body(UUID.randomUUID().toString(),"saveChat");var seen=new AtomicLong();var out=new AtomicTransactionsTest.Capture();
            AtomicHttp.run(request(f,body),out.response,(req,res)->res.getOutputStream().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)),f.db,f.p,()->{},(c,bytes)->{seen.set(bytes);f.files.transientCapacity(c,bytes);});
            assertEquals(200,out.status.get());long expected=AtomicHttp.MAX_MUTATION_RECEIPT_ROW_BYTES+("旧"+prior+body.path("title").asText()+body.path("data")).getBytes(StandardCharsets.UTF_8).length;assertEquals(expected,seen.get());
            body.put("action","deleteChat");try(Connection c=f.connection()){assertEquals(AtomicHttp.MAX_MUTATION_RECEIPT_ROW_BYTES+("旧"+prior).getBytes(StandardCharsets.UTF_8).length,WorkspaceMutationBudget.estimate(c,1,"/api/workspace",body));}
            assertTrue(AtomicDatabase.MAX_COMPETING_TRANSACTION_BYTES>=6_000_000L+2*800+AtomicHttp.MAX_MUTATION_RECEIPT_ROW_BYTES);
        }
    }
    @Test void noSpaceOrAnOversizedHistoricalRowPreservesTheConversationAndSkipsTheHandler()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture();var cfg=new Config(f)){
            old(f,"old","{\"text\":\"keep\"}");f.availableBytes.set(StorageCluster.FLOOR+1);var out=new AtomicTransactionsTest.Capture();var body=body(UUID.randomUUID().toString(),"saveChat");AtomicHttp.run(request(f,body),out.response,(a,b)->fail("Rejected capacity entered mutation"),f.db,f.p,()->{},f.files::transientCapacity);assertEquals(507,out.status.get());assertEquals(body.path("operationId").asText(),ModelGateway.JSON.readTree(out.bytes.toByteArray()).path("operationId").asText());assertEquals(1,f.count("SELECT revision FROM conversations"));assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_request_results"));
            f.availableBytes.set(50_000_000_000L);String historical="中".repeat(1_000_001);try(Connection c=f.connection();PreparedStatement q=c.prepareStatement("UPDATE conversations SET payload=?")){q.setString(1,historical);q.executeUpdate();}
            for(String action:List.of("saveChat","deleteChat")){out=new AtomicTransactionsTest.Capture();AtomicHttp.run(request(f,body(UUID.randomUUID().toString(),action)),out.response,(a,b)->fail("Historical payload must stay readable/unmodified"),f.db,f.p,()->{},f.files::transientCapacity);assertEquals(503,out.status.get());assertEquals("LEGACY_CAPACITY_MIGRATION_REQUIRED",ModelGateway.JSON.readTree(out.bytes.toByteArray()).path("code").asText());}
            try(Connection c=f.connection();ResultSet q=c.createStatement().executeQuery("SELECT payload FROM conversations")){assertTrue(q.next());assertEquals(historical,q.getString(1));}
        }
    }
    @Test void newLargeAcknowledgmentsRollbackButHistoricalCommittedResultsStillQueryAndReplay()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture();var cfg=new Config(f)){
            var rejected=new AtomicTransactionsTest.Capture();AtomicHttp.run(request(f,body(UUID.randomUUID().toString(),"saveChat")),rejected.response,(req,res)->{try(Connection c=AccountServlet.db(f.p)){c.createStatement().executeUpdate("INSERT INTO business VALUES(1,'must rollback')");res.getOutputStream().write(ModelGateway.JSON.writeValueAsBytes(ModelGateway.JSON.createObjectNode().put("text","x".repeat(AtomicHttp.MAX_MUTATION_RECEIPT_BYTES))));}catch(Exception failed){throw new ServletException(failed);}},f.db,f.p,()->{},f.files::transientCapacity);assertEquals(503,rejected.status.get());assertEquals(0,f.count("SELECT COUNT(*) FROM business"));assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_request_results"));
            String op=UUID.randomUUID().toString();var input=body(op,"saveChat");var initial=new AtomicTransactionsTest.Capture();AtomicHttp.run(request(f,input),initial.response,(req,res)->res.getOutputStream().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)),f.db,f.p,()->{},f.files::transientCapacity);assertEquals(200,initial.status.get());
            String text="h".repeat(6*1024*1024);byte[] old=ModelGateway.JSON.writeValueAsBytes(ModelGateway.JSON.createObjectNode().put("text",text));try(Connection c=f.connection();PreparedStatement q=c.prepareStatement("UPDATE starhub_request_results SET response_body=? WHERE operation_id=?")){q.setBytes(1,old);q.setString(2,op);q.executeUpdate();}
            f.availableBytes.set(StorageCluster.FLOOR+1);assertEquals(text,f.db.run(op,c->AtomicHttp.result(c,1,f.generation,op)).path("result").path("text").asText());var replay=new AtomicTransactionsTest.Capture();AtomicHttp.run(request(f,input),replay.response,(a,b)->fail("Committed replay repeated mutation"),f.db,f.p,()->{},(c,n)->fail("Committed replay must not reserve new mutation bytes"));assertEquals(200,replay.status.get());assertArrayEquals(old,replay.bytes.toByteArray());
        }
    }
    @Test void uncalibratedLegacyFileMutationKeepsBytesQuotaAndReadableContent()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture();var cfg=new Config(f)){
            f.files.list(1,ModelGateway.JSON.createObjectNode());String id=UUID.randomUUID().toString();byte[] data=new byte[4096];new Random(9).nextBytes(data);
            try(Connection c=f.connection();PreparedStatement q=c.prepareStatement("INSERT INTO library_files(id,user_id,name,mime,size_bytes,content,storage_node_id,owner_generation) VALUES(?,1,'old.txt','text/plain',?,?,1,?)")){q.setString(1,id);q.setInt(2,data.length);q.setBytes(3,data);q.setString(4,f.generation);q.executeUpdate();c.createStatement().executeUpdate("UPDATE file_storage_users SET used_bytes=4096 WHERE user_id=1");}
            assertArrayEquals(data,f.files.readBinary(1,id).bytes());assertEquals("LEGACY_CAPACITY_MIGRATION_REQUIRED",assertThrows(StorageFailure.class,()->f.files.trash(1,UUID.randomUUID().toString(),id,false)).code);
            try(Connection c=f.connection()){c.createStatement().executeUpdate("UPDATE library_files SET deleted_at=CURRENT_TIMESTAMP");}
            assertEquals("LEGACY_CAPACITY_MIGRATION_REQUIRED",assertThrows(StorageFailure.class,()->f.files.trash(1,UUID.randomUUID().toString(),id,true)).code);assertEquals("LEGACY_CAPACITY_MIGRATION_REQUIRED",assertThrows(StorageFailure.class,()->f.files.delete(1,UUID.randomUUID().toString(),id)).code);assertEquals(1,f.count("SELECT COUNT(*) FROM library_files"));assertEquals(4096,f.count("SELECT used_bytes FROM file_storage_users"));
        }
    }
}
