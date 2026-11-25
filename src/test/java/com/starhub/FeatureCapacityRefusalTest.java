package com.starhub;

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

/** Synthetic H2 and simulated capacity probes; not proof of a real disk or redo/undo peak. */
class FeatureCapacityRefusalTest {
  private static HttpSession session(AtomicTransactionsTest.Fixture f,String nonce){
    var attributes=new HashMap<String,Object>(Map.of("starhub.account.id",1L,"starhub.account.email","user@example.test","starhub.account.generation",f.generation,"starhub.account.hash",f.accountHash));if(nonce!=null)attributes.put(AccountIdleSessions.ATTRIBUTE,nonce);
    var session=mock(HttpSession.class);when(session.getAttribute(anyString())).thenAnswer(i->attributes.get(i.getArgument(0)));return session;
  }
  private static HttpServletRequest status(HttpSession session){
    var request=mock(HttpServletRequest.class);when(request.getMethod()).thenReturn("GET");when(request.getServletPath()).thenReturn("/api/account");when(request.getParameter("sessionStatus")).thenReturn("1");when(request.getSession(false)).thenReturn(session);return request;
  }
  private static HttpServletRequest memory(HttpSession session,String operation)throws Exception {
    var request=mock(HttpServletRequest.class);when(request.getMethod()).thenReturn("POST");when(request.getServletPath()).thenReturn("/api/workspace");when(request.getContentType()).thenReturn("application/json");when(request.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(request.getHeader("X-Starhub-Client")).thenReturn("web");when(request.getSession(false)).thenReturn(session);
    var input=new ByteArrayInputStream(("{\"action\":\"setMemoryEnabled\",\"operationId\":\""+operation+"\",\"expectedEmail\":\"user@example.test\",\"enabled\":true}").getBytes(StandardCharsets.UTF_8));when(request.getInputStream()).thenReturn(new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}});return request;
  }
  @Test void nearFloorRejectsIdleAndMemoryBeforeHandlersAndRollsBackGate()throws Exception {
    try(var f=new AtomicTransactionsTest.Fixture()){
      f.p.setProperty("account.idle.enabled","true");String nonce=UUID.randomUUID().toString();
      try(Connection c=f.connection();Statement q=c.createStatement()){
        q.execute("CREATE TABLE starhub_account_idle_gate(id TINYINT PRIMARY KEY,last_clock_millis BIGINT NOT NULL)");q.execute("INSERT INTO starhub_account_idle_gate VALUES(1,0)");
        q.execute("CREATE TABLE starhub_account_idle_sessions(session_nonce CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,user_generation CHAR(36) NOT NULL,last_activity_millis BIGINT NOT NULL,revoked TINYINT NOT NULL)");c.setAutoCommit(false);AccountIdleSessions.createOrRecover(c,nonce,1,f.generation,true);c.commit();
      }
      Path config=Files.createTempFile("idle-capacity-test-", ".properties");String old=System.getProperty("starhub.account.config");
      try{
        try(OutputStream output=Files.newOutputStream(config)){f.p.store(output,"synthetic");}System.setProperty("starhub.account.config",config.toString());
        long clock=f.count("SELECT last_clock_millis FROM starhub_account_idle_gate"),revision=f.count("SELECT revision FROM file_storage_atomic_gate");f.availableBytes.set(StorageCluster.FLOOR+1);
        var idle=new AtomicTransactionsTest.Capture();AtomicHttp.run(status(session(f,nonce)),idle.response,(req,res)->fail("idle handler must not run"),f.db,f.p,()->{},f.files::transientCapacity);
        assertEquals(507,idle.status.get());assertEquals("INSUFFICIENT_STORAGE",ModelGateway.JSON.readTree(idle.bytes.toByteArray()).path("code").asText());assertEquals(clock,f.count("SELECT last_clock_millis FROM starhub_account_idle_gate"));assertEquals(revision,f.count("SELECT revision FROM file_storage_atomic_gate"));
        f.p.setProperty("account.idle.enabled","false");try(OutputStream output=Files.newOutputStream(config)){f.p.store(output,"synthetic");}
        String op=UUID.randomUUID().toString();var result=new AtomicTransactionsTest.Capture();AtomicHttp.run(memory(session(f,null),op),result.response,(req,res)->fail("memory handler must not run"),f.db,f.p,()->{},f.files::transientCapacity);
        assertEquals(507,result.status.get());assertEquals("INSUFFICIENT_STORAGE",ModelGateway.JSON.readTree(result.bytes.toByteArray()).path("code").asText());assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_request_results"));assertEquals(revision,f.count("SELECT revision FROM file_storage_atomic_gate"));
        System.out.println("SYNTHETIC_CAPACITY_REFUSAL idle_status=507 memory_mutation=507 handlers_invoked=0 committed_gate_changes=0");
      }finally{if(old==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",old);Files.deleteIfExists(config);}
    }
  }
}
