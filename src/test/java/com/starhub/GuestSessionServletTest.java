package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuestSessionServletTest {
    @Test void guestAndAccountSessionsHaveTheSameServerSideIdleLimit()throws Exception{
        assertEquals(600,AccountServlet.SESSION_SECONDS);
        String config=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/webapp/WEB-INF/web.xml"));
        assertTrue(config.contains("<session-timeout>10</session-timeout>"));
    }
    private static HttpSession session(){
        HttpSession s=mock(HttpSession.class);Map<String,Object> values=new HashMap<>();
        when(s.getAttribute(anyString())).thenAnswer(c->values.get(c.getArgument(0)));
        doAnswer(c->{values.put(c.getArgument(0),c.getArgument(1));return null;}).when(s).setAttribute(anyString(),any());return s;
    }
    private static JsonNode call(GuestSessionServlet servlet,HttpSession session,String body,int expected)throws Exception{
        HttpServletRequest req=mock(HttpServletRequest.class);HttpServletResponse res=mock(HttpServletResponse.class);
        when(req.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(req.getHeader("X-Starhub-Client")).thenReturn("web");when(req.getContentType()).thenReturn("application/json");when(req.getSession(true)).thenReturn(session);
        ByteArrayInputStream in=new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));when(req.getInputStream()).thenReturn(new ServletInputStream(){public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){}public int read(){return in.read();}});
        ByteArrayOutputStream out=new ByteArrayOutputStream();when(res.getOutputStream()).thenReturn(new ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(WriteListener l){}public void write(int b){out.write(b);}});
        servlet.doPost(req,res);verify(res).setStatus(expected);return ModelGateway.JSON.readTree(out.toByteArray());
    }
    @Test void guestDataIsScopedToBothSessionAndTabAndOnlyCommittedChunksAreVisible()throws Exception{
        GuestSessionServlet servlet=new GuestSessionServlet();HttpSession a=session(),b=session();String tab="a".repeat(32),other="b".repeat(32),tx="c".repeat(32);
        String prefix="\"tab\":\""+tab+"\",\"field\":\"keys\",\"tx\":\""+tx+"\",\"version\":1,";
        call(servlet,a,"{"+prefix+"\"action\":\"begin\"}",200);
        call(servlet,a,"{"+prefix+"\"action\":\"chunk\",\"index\":1,\"data\":\"x\"}",400);
        call(servlet,a,"{"+prefix+"\"action\":\"chunk\",\"index\":0,\"data\":\"{\\\"openai\\\":\\\"secret\\\"}\"}",200);
        assertFalse(call(servlet,a,"{\"tab\":\""+tab+"\",\"action\":\"get\"}",200).has("keys"));
        call(servlet,a,"{"+prefix+"\"action\":\"commit\"}",200);
        assertEquals("secret",call(servlet,a,"{\"tab\":\""+tab+"\",\"action\":\"get\"}",200).path("keys").path("openai").asText());
        assertFalse(call(servlet,a,"{\"tab\":\""+other+"\",\"action\":\"get\"}",200).has("keys"));
        assertFalse(call(servlet,b,"{\"tab\":\""+tab+"\",\"action\":\"get\"}",200).has("keys"));
    }
    @Test void pagehideSnapshotSupersedesOlderInFlightChunks()throws Exception{
        GuestSessionServlet servlet=new GuestSessionServlet();HttpSession s=session();String tab="d".repeat(32),tx="e".repeat(32),flush="f".repeat(32);
        String prefix="\"tab\":\""+tab+"\",\"field\":\"history\",\"tx\":\""+tx+"\",\"version\":1,";
        call(servlet,s,"{"+prefix+"\"action\":\"begin\"}",200);
        call(servlet,s,"{"+prefix+"\"action\":\"chunk\",\"index\":0,\"data\":\"{\\\"title\\\":\\\"old\\\"}\"}",200);
        assertTrue(call(servlet,s,"{\"tab\":\""+tab+"\",\"action\":\"get\",\"after\":\""+flush+"\"}",200).path("pendingFlush").asBoolean());
        call(servlet,s,"{\"tab\":\""+tab+"\",\"action\":\"snapshot\",\"tx\":\""+flush+"\",\"version\":2,\"values\":{\"history\":{\"title\":\"new\"}}}",200);
        call(servlet,s,"{"+prefix+"\"action\":\"commit\"}",200);
        JsonNode result=call(servlet,s,"{\"tab\":\""+tab+"\",\"action\":\"get\",\"after\":\""+flush+"\"}",200);
        assertFalse(result.path("pendingFlush").asBoolean());assertEquals("new",result.path("history").path("title").asText());
    }
}
