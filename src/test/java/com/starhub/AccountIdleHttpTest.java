package com.starhub;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountIdleHttpTest {
    @Test void statusAndWorkspaceEntryCommitExpiryBeforeRejecting() throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            f.p.setProperty("account.idle.enabled","true");String nonce=UUID.randomUUID().toString();
            try(Connection c=f.connection();Statement q=c.createStatement()){
                q.execute("CREATE TABLE starhub_account_idle_gate(id TINYINT PRIMARY KEY,last_clock_millis BIGINT NOT NULL)");
                q.execute("INSERT INTO starhub_account_idle_gate VALUES(1,0)");
                q.execute("CREATE TABLE starhub_account_idle_sessions(session_nonce CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,user_generation CHAR(36) NOT NULL,last_activity_millis BIGINT NOT NULL,revoked TINYINT NOT NULL)");
                c.setAutoCommit(false);AccountIdleSessions.createOrRecover(c,nonce,1,f.generation,true);c.commit();
            }
            Path config=Files.createTempFile("starhub-idle-http-", ".properties");String old=System.getProperty("starhub.account.config");
            try{
                try(OutputStream out=Files.newOutputStream(config)){f.p.store(out,"test");}System.setProperty("starhub.account.config",config.toString());
                var attrs=new HashMap<String,Object>(Map.of("starhub.account.id",1L,"starhub.account.email","user@example.test","starhub.account.generation",f.generation,"starhub.account.hash",f.accountHash,AccountIdleSessions.ATTRIBUTE,nonce));
                HttpSession session=mock(HttpSession.class);when(session.getAttribute(anyString())).thenAnswer(i->attrs.get(i.getArgument(0)));
                HttpServletRequest req=mock(HttpServletRequest.class);when(req.getMethod()).thenReturn("GET");when(req.getServletPath()).thenReturn("/api/account");when(req.getParameter("sessionStatus")).thenReturn("1");when(req.getSession(anyBoolean())).thenReturn(session);
                var servlet=new AccountServlet();var status=new AtomicTransactionsTest.Capture();
                AtomicHttp.run(req,status.response,(a,b)->servlet.doGet((HttpServletRequest)a,(HttpServletResponse)b),f.db,f.p,()->{},(c,n)->{});
                var result=ModelGateway.JSON.readTree(status.bytes.toByteArray());assertTrue(result.path("signedIn").asBoolean());assertEquals("user@example.test",result.path("email").asText());
                try(Connection c=f.connection();PreparedStatement q=c.prepareStatement("UPDATE starhub_account_idle_sessions SET last_activity_millis=? WHERE session_nonce=?")){q.setLong(1,System.currentTimeMillis()-AccountIdleSessions.IDLE_MILLIS-1000);q.setString(2,nonce);q.executeUpdate();}
                when(req.getParameter("sessionStatus")).thenReturn(null);var rejected=new AtomicTransactionsTest.Capture();
                AtomicHttp.run(req,rejected.response,(a,b)->fail("expired request reached handler"),f.db,f.p,()->{},(c,n)->{});
                assertEquals(401,rejected.status.get());assertEquals("SESSION_ENDED",ModelGateway.JSON.readTree(rejected.bytes.toByteArray()).path("code").asText());
                assertEquals(1,f.count("SELECT revoked FROM starhub_account_idle_sessions"));
                when(req.getParameter("sessionStatus")).thenReturn("1");var ended=new AtomicTransactionsTest.Capture();
                AtomicHttp.run(req,ended.response,(a,b)->servlet.doGet((HttpServletRequest)a,(HttpServletResponse)b),f.db,f.p,()->{},(c,n)->{});
                assertFalse(ModelGateway.JSON.readTree(ended.bytes.toByteArray()).path("signedIn").asBoolean());
            }finally{if(old==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",old);Files.deleteIfExists(config);}
        }
    }
}
