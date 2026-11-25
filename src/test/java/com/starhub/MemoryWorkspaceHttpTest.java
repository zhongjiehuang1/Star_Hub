package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import java.io.OutputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual servlet and atomic filter, isolated SQL and synthetic authority. */
class MemoryWorkspaceHttpTest {
 private AtomicTransactionsTest.Capture call(AtomicPasswordChangeTest.Harness h,ObjectNode body)throws Exception{
  var request=h.request(body);when(request.getServletPath()).thenReturn("/api/workspace");
  var capture=new AtomicTransactionsTest.Capture();FilterChain servlet=(req,res)->new WorkspaceServlet().doPost((HttpServletRequest)req,(HttpServletResponse)res);
  AtomicHttp.run(request,capture.response,servlet,h.f.db,h.f.p,()->{},h.f.files::transientCapacity);return capture;
 }
 private ObjectNode body(String action){return ModelGateway.JSON.createObjectNode().put("action",action).put("expectedEmail","user@example.test");}
 @Test void optInSaveAcrossReadsDeleteAndDisableAreTransactionalAndReceipted()throws Exception{
  try(var h=new AtomicPasswordChangeTest.Harness()){
   h.f.p.setProperty("vault.key",Base64.getEncoder().encodeToString(new byte[32]));try(OutputStream out=Files.newOutputStream(h.config)){h.f.p.store(out,"synthetic only");}
   try(Connection c=h.f.connection();Statement s=c.createStatement()){
    String ddl=Files.readString(Path.of("deploy/migrations/018_account_memory.sql")).replaceAll("(?m)^--.*$","").replace("ENGINE=InnoDB","");for(String sql:ddl.split(";"))if(!sql.isBlank())s.execute(sql);
   }
   var initial=call(h,body("listMemories"));assertEquals(200,initial.status.get());assertFalse(ModelGateway.JSON.readTree(initial.bytes.toByteArray()).path("enabled").asBoolean());
   String operation=UUID.randomUUID().toString();var enable=body("setMemoryEnabled").put("operationId",operation).put("memoryRevision",0).put("enabled",true);
   var enabled=call(h,enable);assertEquals(200,enabled.status.get());assertEquals("COMMITTED",ModelGateway.JSON.readTree(enabled.bytes.toByteArray()).path("operationState").asText());
   var replay=call(h,enable);assertEquals(200,replay.status.get());assertEquals(1,h.f.count("SELECT revision FROM starhub_memory_settings"));
   var save=call(h,body("saveLocalMemory").put("operationId",UUID.randomUUID().toString()).put("memoryRevision",1).put("text","我叫小明"));assertEquals(200,save.status.get());assertFalse(save.bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("小明"));
   var read=call(h,body("listMemories"));assertEquals(200,read.status.get());JsonNode memory=ModelGateway.JSON.readTree(read.bytes.toByteArray()).path("memories").get(0);assertEquals("小明",memory.path("text").asText());
   var disable=call(h,body("setMemoryEnabled").put("operationId",UUID.randomUUID().toString()).put("memoryRevision",2).put("enabled",false));assertEquals(200,disable.status.get());
   var stale=call(h,body("saveLocalMemory").put("operationId",UUID.randomUUID().toString()).put("memoryRevision",2).put("text","我叫小红"));assertEquals(409,stale.status.get());
   var delete=call(h,body("deleteMemory").put("operationId",UUID.randomUUID().toString()).put("memoryRevision",3).put("memoryId",memory.path("id").asText()));assertEquals(200,delete.status.get());
   var empty=call(h,body("listMemories"));assertTrue(ModelGateway.JSON.readTree(empty.bytes.toByteArray()).path("memories").isEmpty());
  }
 }
}
