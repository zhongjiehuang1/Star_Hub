package com.starhub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercise real servlet, atomic receipts, and SQL with two simulated nodes. */
class ConversationDeletePersistenceTest {
    private HttpServletRequest request(AtomicPasswordChangeTest.Harness h,ObjectNode body)throws Exception {
        HttpServletRequest req=mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");when(req.getServletPath()).thenReturn("/api/workspace");
        when(req.getContentType()).thenReturn("application/json");when(req.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");
        when(req.getHeader("X-Starhub-Client")).thenReturn("web");when(req.getSession(anyBoolean())).thenReturn(h.session);
        ByteArrayInputStream bytes=new ByteArrayInputStream(ModelGateway.JSON.writeValueAsBytes(body));
        when(req.getInputStream()).thenReturn(new ServletInputStream(){public int read(){return bytes.read();}public boolean isFinished(){return bytes.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}});
        return req;
    }
    private AtomicTransactionsTest.Capture call(AtomicPasswordChangeTest.Harness h,AtomicDatabase database,ObjectNode body)throws Exception {
        var result=new AtomicTransactionsTest.Capture();
        AtomicHttp.run(request(h,body),result.response,(req,res)->new WorkspaceServlet().doPost((HttpServletRequest)req,(HttpServletResponse)res),database,h.f.p,()->{},h.f.files::transientCapacity);
        return result;
    }
    private ObjectNode body(String action,String op){return ModelGateway.JSON.createObjectNode().put("action",action).put("expectedEmail","user@example.test").put("operationId",op).put("id","chat-delete-test").put("revision",1);}
    @Test void lostDeleteConfirmationCanBeRecoveredOnPeerWithoutResurrectingFromStaleUpdate()throws Exception {
        try(var h=new AtomicPasswordChangeTest.Harness()){
            try(var c=h.f.connection();var q=c.prepareStatement("INSERT INTO conversations(user_id,chat_id,title,payload,revision) VALUES(1,?,?,?,1)")){q.setString(1,"chat-delete-test");q.setString(2,"test");q.setString(3,"{\"messages\":[]}");q.executeUpdate();}
            String id=UUID.randomUUID().toString();ObjectNode deletion=body("deleteChat",id);
            h.f.rejectConfirm.set(true);var unknown=call(h,h.f.db,deletion);assertEquals(503,unknown.status.get());assertEquals(0,h.f.count("SELECT COUNT(*) FROM conversations"));
            h.f.rejectConfirm.set(false);var recovered=call(h,h.peer,deletion);assertEquals(200,recovered.status.get());assertTrue(ModelGateway.JSON.readTree(recovered.bytes.toByteArray()).path("ok").asBoolean());assertEquals(0,h.f.count("SELECT COUNT(*) FROM conversations"));
            ObjectNode stale=body("saveChat",UUID.randomUUID().toString()).put("title","stale");stale.putObject("data").putArray("messages");
            assertEquals(409,call(h,h.peer,stale).status.get());assertEquals(0,h.f.count("SELECT COUNT(*) FROM conversations"));
        }
    }
    @Test void aChangedConversationCannotBeDeletedUsingAnOutdatedRevision()throws Exception {
        try(var h=new AtomicPasswordChangeTest.Harness()){
            try(var c=h.f.connection();var q=c.prepareStatement("INSERT INTO conversations(user_id,chat_id,title,payload,revision) VALUES(1,?,?,?,2)")){q.setString(1,"chat-delete-test");q.setString(2,"newer");q.setString(3,"{\"messages\":[]}");q.executeUpdate();}
            var result=call(h,h.f.db,body("deleteChat",UUID.randomUUID().toString()));
            assertEquals(409,result.status.get());assertEquals(1,h.f.count("SELECT COUNT(*) FROM conversations"));assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_request_results"));
        }
    }
}
