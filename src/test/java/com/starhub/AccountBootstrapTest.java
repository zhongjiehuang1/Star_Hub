package com.starhub;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AccountBootstrapTest {
 @Test void bundleUsesOneConfirmedScopeAndNeverReleasesUnconfirmedOrStaleData()throws Exception{
  try(var f=new AtomicTransactionsTest.Fixture()){
   f.p.setProperty("vault.key",Base64.getEncoder().encodeToString(new byte[32]));
   try(Connection c=f.connection();Statement q=c.createStatement()){
    q.execute("ALTER TABLE conversations ADD updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
    q.execute("CREATE TABLE provider_keys(user_id BIGINT,provider VARCHAR(40),nonce VARBINARY(12),ciphertext VARBINARY(100))");
    q.execute("CREATE TABLE api_keys(id VARCHAR(36),user_id BIGINT,provider VARCHAR(40),label VARCHAR(100),nonce VARBINARY(12),ciphertext VARBINARY(100),selected BOOLEAN,created_at TIMESTAMP)");
    for(int i=0;i<6;i++)q.execute("INSERT INTO conversations(user_id,chat_id,title,payload,revision) VALUES(1,'chat"+i+"','title','{}',1)");
    q.execute("INSERT INTO conversations(user_id,chat_id,title,payload,revision) VALUES(2,'private','other','{}',1)");
   }
   Path config=Files.createTempFile("starhub-bootstrap-", ".properties");String old=System.getProperty("starhub.account.config");
   try{
    try(OutputStream out=Files.newOutputStream(config)){f.p.store(out,"test");}System.setProperty("starhub.account.config",config.toString());
    HttpSession session=mock(HttpSession.class);Map<String,Object> attrs=new HashMap<>(Map.of("starhub.account.id",1L,"starhub.account.email","user@example.test","starhub.account.generation",f.generation,"starhub.account.hash",f.accountHash));
    when(session.getAttribute(anyString())).thenAnswer(i->attrs.get(i.getArgument(0)));when(session.getAttributeNames()).thenAnswer(i->Collections.enumeration(attrs.keySet()));
    HttpServletRequest req=mock(HttpServletRequest.class);when(req.getMethod()).thenReturn("GET");when(req.getServletPath()).thenReturn("/api/account");when(req.getParameter("bootstrap")).thenReturn("1");when(req.getSession(anyBoolean())).thenReturn(session);
    var servlet=new AccountServlet();var success=new AtomicTransactionsTest.Capture();
    AtomicHttp.run(req,success.response,(a,b)->servlet.doGet((HttpServletRequest)a,(HttpServletResponse)b),f.db,f.p,()->{},f.files::transientCapacity);
    var body=ModelGateway.JSON.readTree(success.bytes.toByteArray());assertEquals(200,success.status.get());assertEquals(1,f.confirms.get());var page=body.path("workspaceBootstrap");assertEquals(6,page.path("total").asInt());assertEquals(4,page.path("chats").size());assertEquals(4,page.path("nextOffset").asInt());assertEquals(64,page.path("snapshot").asText().length());
    f.rejectConfirm.set(true);var unknown=new AtomicTransactionsTest.Capture();AtomicHttp.run(req,unknown.response,(a,b)->servlet.doGet((HttpServletRequest)a,(HttpServletResponse)b),f.db,f.p,()->{},f.files::transientCapacity);assertEquals(503,unknown.status.get());assertFalse(unknown.bytes.toString().contains("workspaceBootstrap"));
    f.rejectConfirm.set(false);int before=f.confirms.get();var rolledBack=new AtomicTransactionsTest.Capture();AtomicHttp.run(req,rolledBack.response,(a,b)->{servlet.doGet((HttpServletRequest)a,(HttpServletResponse)b);try{AccountServlet.db(f.p).rollback();}catch(SQLException e){throw new jakarta.servlet.ServletException(e);}},f.db,f.p,()->{},f.files::transientCapacity);assertEquals(503,rolledBack.status.get());assertEquals(before,f.confirms.get());assertFalse(rolledBack.bytes.toString().contains("workspaceBootstrap"));
    when(req.getParameter("bootstrap")).thenReturn(null);var ordinary=new AtomicTransactionsTest.Capture();AtomicHttp.run(req,ordinary.response,(a,b)->servlet.doGet((HttpServletRequest)a,(HttpServletResponse)b),f.db,f.p,()->{},f.files::transientCapacity);assertTrue(ModelGateway.JSON.readTree(ordinary.bytes.toByteArray()).path("signedIn").asBoolean());assertFalse(ModelGateway.JSON.readTree(ordinary.bytes.toByteArray()).has("workspaceBootstrap"));when(req.getParameter("bootstrap")).thenReturn("1");
    attrs.put("starhub.account.generation",UUID.randomUUID().toString());var stale=new AtomicTransactionsTest.Capture();AtomicHttp.run(req,stale.response,(a,b)->servlet.doGet((HttpServletRequest)a,(HttpServletResponse)b),f.db,f.p,()->{},f.files::transientCapacity);var denied=ModelGateway.JSON.readTree(stale.bytes.toByteArray());assertFalse(denied.path("signedIn").asBoolean());assertFalse(denied.has("workspaceBootstrap"));
   }finally{if(old==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",old);Files.deleteIfExists(config);}
  }
 }
}
