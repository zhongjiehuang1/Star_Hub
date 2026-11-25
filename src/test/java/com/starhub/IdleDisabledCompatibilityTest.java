package com.starhub;

import java.io.*;
import java.lang.reflect.*;
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

/** 017 is optional while the global idle switch remains off. */
class IdleDisabledCompatibilityTest {
    private static HttpSession session(Map<String,Object> attributes){
        var session=mock(HttpSession.class);when(session.getAttribute(anyString())).thenAnswer(i->attributes.get(i.getArgument(0)));when(session.getAttributeNames()).thenAnswer(i->Collections.enumeration(attributes.keySet()));
        doAnswer(i->{attributes.put(i.getArgument(0),i.getArgument(1));return null;}).when(session).setAttribute(anyString(),any());when(session.getId()).thenReturn("synthetic-session");return session;
    }
    private static HttpServletRequest post(String path,String body,HttpSession session)throws Exception {
        var request=mock(HttpServletRequest.class);when(request.getMethod()).thenReturn("POST");when(request.getServletPath()).thenReturn(path);when(request.getContentType()).thenReturn("application/json");when(request.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(request.getHeader("X-Starhub-Client")).thenReturn("web");when(request.getRemoteAddr()).thenReturn("192.0.2.1");when(request.getSession(anyBoolean())).thenReturn(session);
        var input=new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));when(request.getInputStream()).thenReturn(new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}});return request;
    }
    @Test void loginAndOrdinaryWorkspaceRequestDoNotNeed017WhenDisabled()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            byte[] salt=new byte[16],password=AccountServlet.hash("synthetic-test-password",salt);
            try(Connection c=f.connection();Statement q=c.createStatement()){
                q.execute("ALTER TABLE conversations ADD updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                for(String sql:Files.readString(Path.of("deploy/migrations/018_account_memory.sql")).replace("ENGINE=InnoDB","").split(";"))if(!sql.isBlank())q.execute(sql);
                try(PreparedStatement update=c.prepareStatement("UPDATE users SET password_salt=?,password_hash=? WHERE id=1")){update.setBytes(1,salt);update.setBytes(2,password);update.executeUpdate();}
            }
            Path config=Files.createTempFile("idle-disabled-", ".properties");String old=System.getProperty("starhub.account.config");
            try{
                try(OutputStream out=Files.newOutputStream(config)){f.p.store(out,"synthetic");}System.setProperty("starhub.account.config",config.toString());
                Map<String,Object> attrs=new HashMap<>();HttpSession session=session(attrs);String op=UUID.randomUUID().toString();
                var login=new AtomicTransactionsTest.Capture();var request=post("/api/account","{\"action\":\"login\",\"email\":\"user@example.test\",\"password\":\"synthetic-test-password\",\"operationId\":\""+op+"\"}",session);
                AtomicHttp.run(request,login.response,(a,b)->new AccountServlet().doPost((HttpServletRequest)a,(HttpServletResponse)b),f.db,f.p,()->{},f.files::transientCapacity);
                assertEquals(200,login.status.get());assertTrue(ModelGateway.JSON.readTree(login.bytes.toByteArray()).path("ok").asBoolean());assertEquals(1L,attrs.get("starhub.account.id"));assertFalse(attrs.containsKey(AccountIdleSessions.ATTRIBUTE));
                var workspace=new AtomicTransactionsTest.Capture();var list=post("/api/workspace","{\"action\":\"listChats\",\"paginationProtocol\":1,\"offset\":0,\"expectedEmail\":\"user@example.test\"}",session);
                AtomicHttp.run(list,workspace.response,(a,b)->new WorkspaceServlet().doPost((HttpServletRequest)a,(HttpServletResponse)b),f.db,f.p,()->{},f.files::transientCapacity);
                assertEquals(200,workspace.status.get());assertEquals(0,ModelGateway.JSON.readTree(workspace.bytes.toByteArray()).path("total").asInt(-1));
                try(Connection c=f.connection();ResultSet r=c.getMetaData().getTables(null,null,"starhub_account_idle_sessions",null)){assertFalse(r.next());}
            }finally{if(old==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",old);Files.deleteIfExists(config);}
        }
    }
    @Test void boundedReadDoesNotRequire017WhenDisabled()throws Exception {
        var helpers=new BoundedChatReadTest();var result=new AtomicTransactionsTest.Capture();Properties config=new Properties();
        Method idle=BoundedChatRead.class.getDeclaredMethod("requireIdleSession",Properties.class,HttpSession.class);idle.setAccessible(true);
        BoundedChatRead.run(helpers.request(helpers.attributes()),result.response,()->config,p->helpers.scope(),(p,s)->{try{idle.invoke(null,p,s);}catch(InvocationTargetException e){throw (Exception)e.getCause();}});
        assertEquals(200,result.status.get());assertTrue(result.bytes.toString().contains("chat-1"));
    }
    @Test void providerNetworkAdmissionDoesNotRequire017WhenDisabled()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            var attrs=new HashMap<String,Object>(Map.of("starhub.account.id",1L,"starhub.account.email","user@example.test","starhub.account.generation",h.f.generation,"starhub.account.hash",h.f.accountHash));
            var session=session(attrs);FileStorage before=FileStorage.active,service=mock(FileStorage.class);
            Field config=FileStorage.class.getDeclaredField("config"),atomic=FileStorage.class.getDeclaredField("atomic");config.setAccessible(true);atomic.setAccessible(true);config.set(service,h.f.p);atomic.set(service,h.f.files);FileStorage.active=service;
            try{
                String op=UUID.randomUUID().toString();var input=h.input(op).put("ownerTab","a".repeat(32)).put("expectedEmail","user@example.test");var owner=ProviderIdentity.from(session,input,h.f.files);
                h.operations.claim(owner,op,"test:generate",input);var ticket=h.operations.dispatch(owner,op,"test:generate",input);
                try(var context=ticket.enter()){assertDoesNotThrow(ProviderOperations::beforeExternal);}assertTrue(ticket.attempted());
            }finally{FileStorage.active=before;}
        }
    }
}
