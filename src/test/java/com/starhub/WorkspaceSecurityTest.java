package com.starhub;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WorkspaceSecurityTest {
 @Test void limitsAndTypesAreExplicit(){assertEquals(2_000_000_000L,WorkspaceServlet.QUOTA);assertEquals(4*1024*1024,WorkspaceServlet.FILE_MAX);assertTrue(WorkspaceServlet.TYPES.contains("image/png"));assertFalse(WorkspaceServlet.TYPES.contains("text/html"));}
 @Test void missingOriginAndAnonymousUsersCannotWrite()throws Exception{
  WorkspaceServlet servlet=new WorkspaceServlet();HttpServletRequest req=mock(HttpServletRequest.class);HttpServletResponse res=mock(HttpServletResponse.class);
  ByteArrayOutputStream b=new ByteArrayOutputStream();jakarta.servlet.ServletOutputStream out=new jakarta.servlet.ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(jakarta.servlet.WriteListener w){}public void write(int c){b.write(c);}};when(res.getOutputStream()).thenReturn(out);
  servlet.doPost(req,res);verify(res).setStatus(403);
  reset(res);when(res.getOutputStream()).thenReturn(out);when(req.getHeader("X-Starhub-Client")).thenReturn("web");when(req.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(req.getContentType()).thenReturn("application/json");servlet.doPost(req,res);verify(res).setStatus(401);
 }
 @Test void twoImagesAreIncludedInProviderRequest() throws Exception {
  com.fasterxml.jackson.databind.node.ObjectNode r=new ModelGatewayTest().request("openai").put("model","gpt-6.1-sol");((com.fasterxml.jackson.databind.node.ObjectNode)r.withArray("messages").get(0)).putArray("images").add("data:image/png;base64,AAAA").add("data:image/png;base64,AQID");
  com.fasterxml.jackson.databind.JsonNode parts=ModelGateway.prepare(r,true).body.path("input").path(1).path("content");assertEquals("input_image",parts.path(1).path("type").asText());assertEquals("input_image",parts.path(2).path("type").asText());
 }
}
