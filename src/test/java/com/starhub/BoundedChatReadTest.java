package com.starhub;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BoundedChatReadTest {
    BoundedReadScope scope()throws Exception {
        Connection raw=data();Connection wrapped=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("createStatement"))return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(sp,sm,sa)->{
                if(sm.getName().equals("executeQuery")){String sql=(String)sa[0];if(sql.contains("group_replication_consistency"))return raw.createStatement().executeQuery("SELECT 'uuid:1-2','BEFORE_AND_AFTER'");if(sql.equals("SELECT @@global.gtid_executed"))return raw.createStatement().executeQuery("SELECT 'uuid:1-9'");}
                if(sm.getName().equals("execute"))return false;if(sm.getName().equals("close"))return null;throw new SQLException("Unexpected test statement");
            });
            try{return m.invoke(raw,a);}catch(InvocationTargetException e){throw e.getCause();}
        });
        var lease=new ReplicaAuthority.Lease("uuid","permit","view","1","dual","boot","members");
        var authority=new BoundedReadScope.Authority(){public ReplicaAuthority.Lease acquire(){return lease;}public void unchanged(ReplicaAuthority.Lease l){}public void confirm(ReplicaAuthority.Lease l,String g,String id){}};
        return new BoundedReadScope(wrapped,authority,lease);
    }
    HttpServletRequest request(Map<String,Object> attrs)throws Exception {
        HttpSession session=mock(HttpSession.class);when(session.getAttribute(anyString())).thenAnswer(i->attrs.get(i.getArgument(0)));
        HttpServletRequest request=mock(HttpServletRequest.class);when(request.getMethod()).thenReturn("POST");when(request.getServletPath()).thenReturn("/api/workspace");when(request.getHeader("X-Starhub-Client")).thenReturn("web");when(request.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(request.getContentType()).thenReturn("application/json");when(request.getSession(false)).thenReturn(session);
        var input=new ByteArrayInputStream("{\"action\":\"listChats\",\"paginationProtocol\":1,\"offset\":0,\"expectedEmail\":\"owner@example.test\"}".getBytes(StandardCharsets.UTF_8));
        when(request.getInputStream()).thenReturn(new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}});return request;
    }
    Map<String,Object> attributes(){return new HashMap<>(Map.of("starhub.account.id",1L,"starhub.account.email","owner@example.test","starhub.account.generation","g","starhub.account.hash",new byte[]{1,2}));}
    @Test void boundedRouteRequiresIdleAdmissionBeforeAndAfterReading()throws Exception {
        var config=new Properties();config.setProperty("account.idle.enabled","true");
        var rejected=new AtomicTransactionsTest.Capture();AtomicInteger opens=new AtomicInteger();
        BoundedChatRead.run(request(attributes()),rejected.response,()->config,p->{opens.incrementAndGet();return scope();},(p,s)->{throw new StorageFailure(401,"SESSION_ENDED","ended");});
        assertEquals(401,rejected.status.get());assertEquals(0,opens.get());assertFalse(rejected.bytes.toString().contains("chat-1"));
        var expiredAfterRead=new AtomicTransactionsTest.Capture();AtomicInteger checks=new AtomicInteger();
        BoundedChatRead.run(request(attributes()),expiredAfterRead.response,()->config,p->scope(),(p,s)->{if(checks.incrementAndGet()==2)throw new StorageFailure(401,"SESSION_ENDED","ended");});
        assertEquals(2,checks.get());assertEquals(401,expiredAfterRead.status.get());assertFalse(expiredAfterRead.bytes.toString().contains("chat-1"));
        var allowed=new AtomicTransactionsTest.Capture();checks.set(0);
        BoundedChatRead.run(request(attributes()),allowed.response,()->config,p->scope(),(p,s)->checks.incrementAndGet());
        assertEquals(2,checks.get());assertEquals(200,allowed.status.get());assertTrue(allowed.bytes.toString().contains("chat-1"));
    }
    @Test void boundedRouteNeverReturnsAFormerAccountsBytesAfterSessionChanges()throws Exception {
        var config=new Properties();var attrs=attributes();AtomicInteger checks=new AtomicInteger();var denied=new AtomicTransactionsTest.Capture();
        BoundedChatRead.run(request(attrs),denied.response,()->config,p->scope(),(p,s)->{if(checks.incrementAndGet()==2)attrs.put("starhub.account.email","other@example.test");});
        assertEquals(401,denied.status.get());assertFalse(denied.bytes.toString().contains("chat-1"));
    }
    @Test void boundedRouteCommitsTheActualIdleRevocationBeforeRejectingRead()throws Exception {
        try(Connection idle=DriverManager.getConnection("jdbc:h2:mem:idle-bounded-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");Statement q=idle.createStatement()){
            q.execute("CREATE TABLE starhub_account_idle_gate(id TINYINT PRIMARY KEY,last_clock_millis BIGINT NOT NULL)");q.execute("INSERT INTO starhub_account_idle_gate VALUES(1,0)");
            q.execute("CREATE TABLE starhub_account_idle_sessions(session_nonce CHAR(36) PRIMARY KEY,user_id BIGINT,user_generation VARCHAR,last_activity_millis BIGINT,revoked TINYINT)");
            String nonce=UUID.randomUUID().toString();try(PreparedStatement insert=idle.prepareStatement("INSERT INTO starhub_account_idle_sessions VALUES(?,1,'g',?,0)")){insert.setString(1,nonce);insert.setLong(2,System.currentTimeMillis()-AccountIdleSessions.IDLE_MILLIS-1000);insert.executeUpdate();}
            idle.setAutoCommit(false);var response=new AtomicTransactionsTest.Capture();var config=new Properties();config.setProperty("account.idle.enabled","true");
            BoundedChatRead.run(request(attributes()),response.response,()->config,p->scope(),(p,s)->{long remaining=AccountIdleSessions.remaining(idle,nonce,1,"g");idle.commit();if(remaining==0)throw new StorageFailure(401,"SESSION_ENDED","ended");});
            assertEquals(401,response.status.get());assertFalse(response.bytes.toString().contains("chat-1"));
            try(ResultSet r=q.executeQuery("SELECT revoked FROM starhub_account_idle_sessions")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
        }
    }
    private Connection data()throws Exception {
        Connection c=DriverManager.getConnection("jdbc:h2:mem:bounded-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        try(Statement q=c.createStatement()){
            q.execute("CREATE TABLE users(id BIGINT,password_hash BINARY(2),email VARCHAR,generation VARCHAR,active INT)");
            q.execute("INSERT INTO users VALUES(1,X'0102','owner@example.test','g',1)");
            q.execute("CREATE TABLE conversations(user_id BIGINT,chat_id VARCHAR(100),title VARCHAR(200),revision BIGINT,payload CLOB,updated_at TIMESTAMP)");
            q.execute("INSERT INTO conversations VALUES(1,'chat-1','Owner',1,'{\"messages\":[]}',CURRENT_TIMESTAMP)");
            q.execute("INSERT INTO conversations VALUES(2,'chat-2','Other',1,'{\"secret\":true}',CURRENT_TIMESTAMP)");
        }return c;
    }
    @Test void exactSelectProxyRejectsMutationAndEveryEscape()throws Exception {
        try(Connection raw=data()){
            Connection c=BoundedReadScope.readOnly(raw);
            assertThrows(SQLException.class,()->c.createStatement());assertThrows(SQLException.class,()->c.commit());assertThrows(SQLException.class,()->c.unwrap(Connection.class));
            assertThrows(SQLException.class,()->c.prepareStatement("UPDATE users SET active=0"));
            assertThrows(SQLException.class,()->c.prepareStatement("SELECT password_hash,email,generation FROM users WHERE id=? AND active=1 FOR UPDATE"));
            assertThrows(SQLException.class,()->c.prepareStatement("SELECT user_defined_side_effect()"));
            try(PreparedStatement q=c.prepareStatement("SELECT password_hash,email,generation FROM users WHERE id=? AND active=1")){
                q.setLong(1,1);assertThrows(SQLException.class,()->q.executeUpdate());assertThrows(SQLException.class,()->q.getConnection());assertThrows(SQLException.class,()->q.addBatch());
                try(ResultSet r=q.executeQuery()){assertTrue(r.next());assertEquals("owner@example.test",r.getString(2));assertThrows(SQLException.class,()->r.getStatement());assertThrows(SQLException.class,()->r.updateString(2,"changed"));}
            }
            try(Statement q=raw.createStatement();ResultSet r=q.executeQuery("SELECT active FROM users WHERE id=1")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
        }
    }
    @Test void boundedPagePreservesOwnerAndCursorAndRefusesBeforeOversizeFetch()throws Exception {
        try(Connection raw=data()){
            var request=ModelGateway.JSON.createObjectNode().put("paginationProtocol",1).put("offset",0);
            var page=ConversationPages.loadBounded(BoundedReadScope.readOnly(raw),1,"g",request);
            assertEquals(1,page.path("total").asInt());assertEquals("chat-1",page.path("chats").get(0).path("id").asText());assertTrue(BoundedChatRead.encode(page).length<BoundedChatRead.OUTPUT_LIMIT);
            try(PreparedStatement q=raw.prepareStatement("UPDATE conversations SET payload=? WHERE user_id=1")){q.setString(1,"{\"draft\":\""+"x".repeat(33000)+"\"}");q.executeUpdate();}
            assertEquals("BOUNDED_CHAT_FALLBACK",assertThrows(StorageFailure.class,()->ConversationPages.loadBounded(BoundedReadScope.readOnly(raw),1,"g",request)).code);
            assertEquals(33000,ConversationPages.load(raw,1,"g",request).path("chats").get(0).path("data").path("draft").asText().length());
        }
    }
    @Test void authenticationRequiresSameOwnerGenerationAndCurrentHash()throws Exception {
        try(Connection raw=data()){
            var attributes=new HashMap<String,Object>();attributes.put("starhub.account.id",1L);attributes.put("starhub.account.email","owner@example.test");attributes.put("starhub.account.generation","g");attributes.put("starhub.account.hash",new byte[]{1,2});
            var session=(jakarta.servlet.http.HttpSession)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{jakarta.servlet.http.HttpSession.class},(p,m,a)->{if(m.getName().equals("getAttribute"))return attributes.get(a[0]);throw new UnsupportedOperationException();});
            var request=ModelGateway.JSON.createObjectNode().put("expectedEmail","owner@example.test");
            assertEquals(1,BoundedChatRead.authenticate(BoundedReadScope.readOnly(raw),session,request).id());
            attributes.put("starhub.account.generation","revoked");assertEquals(401,assertThrows(StorageFailure.class,()->BoundedChatRead.authenticate(BoundedReadScope.readOnly(raw),session,request)).status);
            attributes.put("starhub.account.generation","g");attributes.put("starhub.account.hash",new byte[]{9,9});assertEquals(401,assertThrows(StorageFailure.class,()->BoundedChatRead.authenticate(BoundedReadScope.readOnly(raw),session,request)).status);
            attributes.put("starhub.account.hash",new byte[]{1,2});request.put("expectedEmail","other@example.test");assertEquals(409,assertThrows(StorageFailure.class,()->BoundedChatRead.authenticate(BoundedReadScope.readOnly(raw),session,request)).status);
        }
    }
    @Test void encodedEscapingCannotExceedCapBeforeFallback()throws Exception {
        var value=ModelGateway.JSON.createObjectNode().put("payload","\u0001".repeat(50000));
        assertEquals("BOUNDED_CHAT_FALLBACK",assertThrows(StorageFailure.class,()->BoundedChatRead.encode(value)).code);
    }
    @Test void endGtidIncludesTransactionsAfterInitialBarrierAndSnapshot()throws Exception {
        try(Connection raw=data()){
            List<String> events=new ArrayList<>();AtomicReference<String> confirmed=new AtomicReference<>();
            Connection wrapped=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("createStatement"))return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(sp,sm,sa)->{
                    if(sm.getName().equals("executeQuery")){
                        String sql=(String)sa[0];events.add(sql);
                        if(sql.contains("group_replication_consistency"))return raw.createStatement().executeQuery("SELECT 'uuid:1-2','BEFORE_AND_AFTER'");
                        if(sql.equals("SELECT @@global.gtid_executed"))return raw.createStatement().executeQuery("SELECT 'uuid:1-9'");
                    }
                    if(sm.getName().equals("execute")){assertEquals("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY",sa[0]);assertFalse(raw.getAutoCommit(),"JDBC transaction state must be explicit before START TRANSACTION");events.add("snapshot");return false;}
                    if(sm.getName().equals("close"))return null;throw new SQLException("Unexpected test statement");
                });
                try{return m.invoke(raw,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
            var lease=new ReplicaAuthority.Lease("uuid","permit","view","1","dual","boot","members");
            var authority=new BoundedReadScope.Authority(){public ReplicaAuthority.Lease acquire(){return lease;}public void unchanged(ReplicaAuthority.Lease l){events.add("authority");}public void confirm(ReplicaAuthority.Lease l,String g,String id){confirmed.set(g);events.add("confirm");}};
            try(BoundedReadScope scope=new BoundedReadScope(wrapped,authority,lease)){
                ConversationPages.loadBounded(scope.view,1,"g",ModelGateway.JSON.createObjectNode().put("paginationProtocol",1));events.add("read-finished");assertEquals("dual",scope.finish());
                assertEquals("uuid:1-9",confirmed.get());assertTrue(events.indexOf("SELECT @@global.gtid_executed")>events.indexOf("read-finished"));assertTrue(events.indexOf("confirm")>events.indexOf("SELECT @@global.gtid_executed"));
            }
        }
    }
}
