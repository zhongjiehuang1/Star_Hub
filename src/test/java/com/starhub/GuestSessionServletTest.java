package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real local SQL; authority and capacity are explicit test adapters, not HA evidence. */
class GuestSessionServletTest {
    static final class Harness implements AutoCloseable {
        final AtomicTransactionsTest.Fixture f=new AtomicTransactionsTest.Fixture();final AtomicGuestStorage store;
        Harness()throws Exception {
            f.p.setProperty("vault.key",Base64.getEncoder().encodeToString(new byte[32]));
            StringBuilder cleaned=new StringBuilder();for(String line:Files.readString(Path.of("deploy/migrations/010_atomic_guest_sessions.sql")).split("\n"))if(!line.stripLeading().startsWith("--"))cleaned.append(line).append('\n');
            try(Connection c=f.connection();Statement q=c.createStatement()){for(String sql:cleaned.toString().split(";"))if(!sql.isBlank())q.execute(sql.replace("ENGINE=InnoDB",""));}
            store=other();
        }
        AtomicGuestStorage other()throws Exception{return new AtomicGuestStorage(new AtomicDatabase(f.p,f::connection,f.authority),f.p,(c,bytes)->{});}
        public void close(){f.close();}
    }
    @Test void guestAndAccountSessionsHaveTheSameServerSideIdleLimit()throws Exception {
        assertEquals(600,AccountServlet.SESSION_SECONDS);assertEquals(600_000,AtomicGuestStorage.IDLE_MILLIS);
        assertTrue(Files.readString(Path.of("src/main/webapp/WEB-INF/web.xml")).contains("<session-timeout>10</session-timeout>"));
    }
    static HttpSession session(){
        HttpSession s=mock(HttpSession.class);Map<String,Object> values=new HashMap<>();when(s.getId()).thenReturn(UUID.randomUUID().toString());
        when(s.getAttribute(anyString())).thenAnswer(c->values.get(c.getArgument(0)));doAnswer(c->{values.put(c.getArgument(0),c.getArgument(1));return null;}).when(s).setAttribute(anyString(),any());return s;
    }
    static JsonNode call(GuestSessionServlet servlet,HttpSession session,String body,int expected)throws Exception {
        HttpServletRequest req=mock(HttpServletRequest.class);HttpServletResponse res=mock(HttpServletResponse.class);
        when(req.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(req.getHeader("X-Starhub-Client")).thenReturn("web");when(req.getContentType()).thenReturn("application/json");when(req.getSession(true)).thenReturn(session);
        ByteArrayInputStream in=new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));when(req.getInputStream()).thenReturn(new ServletInputStream(){public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){}public int read(){return in.read();}});
        ByteArrayOutputStream out=new ByteArrayOutputStream();when(res.getOutputStream()).thenReturn(new ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(WriteListener l){}public void write(int b){out.write(b);}});
        servlet.doPost(req,res);verify(res).setStatus(expected);return ModelGateway.JSON.readTree(out.toByteArray());
    }
    @Test void guestDataIsScopedToSessionAndTabAndOnlyCommittedChunksAreVisible()throws Exception {
        try(Harness h=new Harness()){
            GuestSessionServlet servlet=new GuestSessionServlet(h.store);HttpSession a=session(),b=session();String tab="a".repeat(32),other="b".repeat(32),tx="c".repeat(32);
            String prefix="\"tab\":\""+tab+"\",\"field\":\"keys\",\"tx\":\""+tx+"\",\"version\":1,";
            call(servlet,a,"{"+prefix+"\"action\":\"begin\"}",200);call(servlet,a,"{"+prefix+"\"action\":\"chunk\",\"index\":1,\"data\":\"x\"}",400);
            call(servlet,a,"{"+prefix+"\"action\":\"chunk\",\"index\":0,\"data\":\"{\\\"openai\\\":\\\"test-only-guest-key\\\"}\"}",200);
            assertFalse(call(servlet,a,"{\"tab\":\""+tab+"\",\"action\":\"get\"}",200).has("keys"));call(servlet,a,"{"+prefix+"\"action\":\"commit\"}",200);
            assertEquals("test-only-guest-key",call(servlet,a,"{\"tab\":\""+tab+"\",\"action\":\"get\"}",200).path("keys").path("openai").asText());
            assertFalse(call(servlet,a,"{\"tab\":\""+other+"\",\"action\":\"get\"}",200).has("keys"));assertFalse(call(servlet,b,"{\"tab\":\""+tab+"\",\"action\":\"get\"}",200).has("keys"));
            assertInstanceOf(String.class,a.getAttribute(GuestSessionServlet.IDENTITY));verify(a,never()).setAttribute(eq("starhub.guest.tabs"),any());
        }
    }
    @Test void pagehideSnapshotRejectsAnOlderInFlightCommitWithoutLosingTheNewerValue()throws Exception {
        try(Harness h=new Harness()){
            GuestSessionServlet servlet=new GuestSessionServlet(h.store);HttpSession s=session();String tab="d".repeat(32),tx="e".repeat(32),flush="f".repeat(32);
            String prefix="\"tab\":\""+tab+"\",\"field\":\"history\",\"tx\":\""+tx+"\",\"version\":1,";
            call(servlet,s,"{"+prefix+"\"action\":\"begin\"}",200);call(servlet,s,"{"+prefix+"\"action\":\"chunk\",\"index\":0,\"data\":\"{\\\"title\\\":\\\"old\\\"}\"}",200);
            assertTrue(call(servlet,s,"{\"tab\":\""+tab+"\",\"action\":\"get\",\"after\":\""+flush+"\"}",200).path("pendingFlush").asBoolean());
            call(servlet,s,"{\"tab\":\""+tab+"\",\"action\":\"snapshot\",\"tx\":\""+flush+"\",\"version\":2,\"values\":{\"history\":{\"title\":\"new\"}}}",200);
            assertEquals("GUEST_VERSION_CONFLICT",call(servlet,s,"{"+prefix+"\"action\":\"commit\"}",409).path("code").asText());
            JsonNode result=call(servlet,s,"{\"tab\":\""+tab+"\",\"action\":\"get\",\"after\":\""+flush+"\"}",200);assertFalse(result.path("pendingFlush").asBoolean());assertEquals("new",result.path("history").path("title").asText());
        }
    }
}
