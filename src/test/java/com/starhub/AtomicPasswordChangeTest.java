package com.starhub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual servlet/wrapper/local SQL; authority and GTID are explicit test adapters. */
class AtomicPasswordChangeTest {
    static final String OLD="test-only-original-password",NEXT="test-only-next-password";
    static final class Harness implements AutoCloseable {
        final AtomicTransactionsTest.Fixture f=new AtomicTransactionsTest.Fixture();
        final AtomicDatabase peer=new AtomicDatabase(f.p,f::connection,f.authority);
        final Map<String,Object> attributes=new ConcurrentHashMap<>();final HttpSession session=mock(HttpSession.class);
        final Path config=Files.createTempFile("starhub-synthetic-password-",".properties");final String previous=System.getProperty("starhub.account.config");
        Harness()throws Exception {
            byte[] salt=new byte[16],hash=AccountServlet.hash(OLD,salt);
            try(Connection c=f.connection();PreparedStatement update=c.prepareStatement("UPDATE users SET password_salt=?,password_hash=? WHERE id=1")){update.setBytes(1,salt);update.setBytes(2,hash);update.executeUpdate();}
            attributes.put("starhub.account.id",1L);attributes.put("starhub.account.email","user@example.test");attributes.put("starhub.account.generation",f.generation);attributes.put("starhub.account.hash",hash);
            when(session.getAttribute(anyString())).thenAnswer(i->attributes.get(i.getArgument(0)));when(session.getAttributeNames()).thenAnswer(i->Collections.enumeration(attributes.keySet()));when(session.getId()).thenReturn("test-only-session");
            doAnswer(i->{attributes.put(i.getArgument(0),i.getArgument(1));return null;}).when(session).setAttribute(anyString(),any());doAnswer(i->{attributes.remove(i.getArgument(0));return null;}).when(session).removeAttribute(anyString());
            try(OutputStream out=Files.newOutputStream(config)){f.p.store(out,"Synthetic unit-test configuration only");}System.setProperty("starhub.account.config",config.toString());
        }
        ObjectNode input(String id,String old,String next){return ModelGateway.JSON.createObjectNode().put("action","changePassword").put("expectedEmail","user@example.test").put("operationId",id).put("currentPassword",old).put("newPassword",next);}
        HttpServletRequest request(ObjectNode body)throws Exception {
            HttpServletRequest req=mock(HttpServletRequest.class);when(req.getMethod()).thenReturn("POST");when(req.getServletPath()).thenReturn(body.path("action").asText().equals("requestStatus")?"/api/workspace":"/api/account");when(req.getContentType()).thenReturn("application/json");when(req.getRemoteAddr()).thenReturn("192.0.2.13");
            when(req.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(req.getHeader("X-Starhub-Client")).thenReturn("web");when(req.getSession(anyBoolean())).thenReturn(session);
            ByteArrayInputStream bytes=new ByteArrayInputStream(ModelGateway.JSON.writeValueAsBytes(body));when(req.getInputStream()).thenReturn(new ServletInputStream(){public int read(){return bytes.read();}public boolean isFinished(){return bytes.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}});return req;
        }
        AtomicTransactionsTest.Capture call(AtomicDatabase database,ObjectNode body)throws Exception {
            var result=new AtomicTransactionsTest.Capture();FilterChain servlet=body.path("action").asText().equals("requestStatus")?(req,res)->new WorkspaceServlet().doPost((HttpServletRequest)req,(HttpServletResponse)res):(req,res)->new AccountServlet().doPost((HttpServletRequest)req,(HttpServletResponse)res);
            AtomicHttp.run(request(body),result.response,servlet,database,f.p,()->{},f.files::transientCapacity);return result;
        }
        byte[] hash()throws Exception {try(Connection c=f.connection();ResultSet r=c.createStatement().executeQuery("SELECT password_hash FROM users WHERE id=1")){assertTrue(r.next());return r.getBytes(1);}}
        public void close()throws Exception {if(previous==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",previous);Files.deleteIfExists(config);f.close();}
    }
    @Test void lostFailureConfirmationReplaysAcrossCoordinatorsAndCountsOneGuess()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();ObjectNode request=h.input(id,"test-only-wrong-password",NEXT);h.f.rejectConfirm.set(true);var unknown=h.call(h.f.db,request);
            assertEquals(503,unknown.status.get());assertEquals(1,h.f.count("SELECT failures FROM starhub_login_limits"));assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_request_results"));assertTrue(unknown.bytes.toString(StandardCharsets.UTF_8).contains("UNKNOWN"));
            h.f.rejectConfirm.set(false);var replay=h.call(h.peer,request);assertEquals(403,replay.status.get());assertTrue(replay.bytes.toString(StandardCharsets.UTF_8).contains("\"operationState\":\"COMMITTED\""));verify(replay.response).setHeader("X-Four-Project-Durability","dual");assertEquals(1,h.f.count("SELECT failures FROM starhub_login_limits"));assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_login_attempts"));
            var changed=h.call(h.peer,h.input(id,"another-test-only-wrong-password",NEXT));assertEquals(409,changed.status.get());assertEquals(1,h.f.count("SELECT failures FROM starhub_login_limits"));
            var known=h.call(h.peer,ModelGateway.JSON.createObjectNode().put("action","requestStatus").put("expectedEmail","user@example.test").put("operationId",id));assertEquals(200,known.status.get());assertEquals(403,ModelGateway.JSON.readTree(known.bytes.toByteArray()).path("status").asInt());verify(known.response).setHeader("X-Four-Project-Durability","dual");
        }
    }
    @Test void actorLimitIsSharedAcrossNodesAndInvalidInputDoesNotCountAsAnotherGuess()throws Exception {
        try(Harness h=new Harness()){
            for(int i=0;i<8;i++)assertEquals(403,h.call(i%2==0?h.f.db:h.peer,h.input(UUID.randomUUID().toString(),"test-only-wrong-"+i,NEXT)).status.get());
            var limited=h.call(h.peer,h.input(UUID.randomUUID().toString(),OLD,NEXT));assertEquals(429,limited.status.get());assertTrue(limited.bytes.toString(StandardCharsets.UTF_8).contains("COMMITTED"));assertEquals(8,h.f.count("SELECT failures FROM starhub_login_limits"));
            var invalid=h.call(h.f.db,h.input(UUID.randomUUID().toString(),OLD,"short"));assertEquals(400,invalid.status.get());assertTrue(invalid.bytes.toString(StandardCharsets.UTF_8).contains("COMMITTED"));assertEquals(8,h.f.count("SELECT failures FROM starhub_login_limits"));assertEquals(9,h.f.count("SELECT COUNT(*) FROM starhub_login_attempts"));
        }
    }
    @Test void committedPasswordWithLostAcknowledgementRequiresNewAuthenticationButNeverRunsTwice()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();ObjectNode input=h.input(id,OLD,NEXT);byte[] old=h.hash();h.f.rejectConfirm.set(true);assertEquals(503,h.call(h.f.db,input).status.get());byte[] current=h.hash();assertFalse(Arrays.equals(old,current));assertArrayEquals(old,(byte[])h.attributes.get("starhub.account.hash"));
            h.f.rejectConfirm.set(false);assertEquals(401,h.call(h.peer,input).status.get());assertArrayEquals(current,h.hash());
            // Simulate the independently verified current-password login; a status
            // receipt itself cannot create or refresh an authenticated session.
            h.attributes.put("starhub.account.hash",current);var replay=h.call(h.peer,input);assertEquals(200,replay.status.get());assertArrayEquals(current,h.hash());assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_request_results"));assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_login_attempts"));assertEquals(0,h.f.count("SELECT failures FROM starhub_login_limits"));
        }
    }
    @Test void sameOperationArrivingOnTwoCoordinatorsHasOneReceiptAndOneFailureCount()throws Exception {
        try(Harness h=new Harness()){
            String id=UUID.randomUUID().toString();ObjectNode input=h.input(id,"test-only-wrong",NEXT);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
            try{Future<AtomicTransactionsTest.Capture> a=pool.submit(()->{start.await();return h.call(h.f.db,input);}),b=pool.submit(()->{start.await();return h.call(h.peer,input);});start.countDown();assertTrue(Set.of(403,429).contains(a.get(10,TimeUnit.SECONDS).status.get()));assertTrue(Set.of(403,429).contains(b.get(10,TimeUnit.SECONDS).status.get()));assertEquals(403,h.call(h.peer,input).status.get());assertEquals(1,h.f.count("SELECT failures FROM starhub_login_limits"));assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_request_results"));}finally{pool.shutdownNow();}
        }
    }
}
