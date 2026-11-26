package com.starhub;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HeavyRequestAdmissionTest {
    private HttpServletRequest request(String path,InputStream stream)throws Exception {
        var req=mock(HttpServletRequest.class);when(req.getMethod()).thenReturn("POST");when(req.getServletPath()).thenReturn(path);when(req.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(req.getHeader("X-Starhub-Client")).thenReturn("web");when(req.getContentType()).thenReturn("application/json");
        when(req.getInputStream()).thenReturn(new ServletInputStream(){public int read()throws IOException{return stream.read();}public boolean isFinished(){return false;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}});return req;
    }
    private HttpServletRequest request(String path,String body)throws Exception{return request(path,new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));}
    private void free(){var lease=ProviderAdmission.tryEnter();assertNotNull(lease);lease.close();}
    private String login(String id){return ModelGateway.JSON.createObjectNode().put("action","login").put("email","unknown@example.test").put("password","synthetic-test-only-password").put("operationId",id).toString();}
    @Test void signedInStandardChatPageWaitsForTheSameHeavyPermit() throws Exception {
        String path="/api/workspace",email="user@example.test";
        String page=ModelGateway.JSON.createObjectNode().put("action","listChats").put("expectedEmail",email)
            .put("paginationProtocol",1).put("offset",0).toString();
        var request=request(path,page);
        var session=mock(HttpSession.class);
        when(session.getAttribute("starhub.account.id")).thenReturn(1L);
        when(session.getAttribute("starhub.account.email")).thenReturn(email);
        var worker=Executors.newSingleThreadExecutor();
        try(var busy=ProviderAdmission.tryEnter()){
            assertNotNull(busy);
            var stranger=request(path,page.replace(email,"other@example.test"));
            when(stranger.getSession(false)).thenReturn(session);
            assertEquals("MEMORY_BUSY",assertThrows(StorageFailure.class,()->HeavyRequestBody.read(stranger,16384,path)).code);
            var readStarted=new CountDownLatch(1);
            when(request.getSession(false)).thenAnswer(i->{readStarted.countDown();return session;});
            var result=worker.submit(()->HeavyRequestBody.read(request,16384,path));
            assertTrue(readStarted.await(2,TimeUnit.SECONDS));
            assertFalse(result.isDone());
            busy.close();
            try(var body=result.get(2,TimeUnit.SECONDS)){
                assertArrayEquals(page.getBytes(StandardCharsets.UTF_8),body.bytes);
                assertNull(ProviderAdmission.tryEnter());
            }
        }finally{worker.shutdownNow();}
        free();
    }
    @Test void guestCommitCanFinishOnItsFirstRequestWhenAChatPageOwnsThePermit() throws Exception {
        String path="/api/guest-session",commit="{\"action\":\"commit\"}";
        var guest=request(path,commit);
        var worker=Executors.newSingleThreadExecutor();
        var started=new CountDownLatch(1);
        try(var page=ProviderAdmission.enterChatRead()){
            assertNotNull(page);
            var stage=worker.submit(()->{started.countDown();return HeavyRequestBody.read(guest,16384,path);});
            assertTrue(started.await(2,TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,()->stage.get(100,TimeUnit.MILLISECONDS));
            page.close();
            try(var body=stage.get(2,TimeUnit.SECONDS)){
                assertArrayEquals(commit.getBytes(StandardCharsets.UTF_8),body.bytes);
                assertNull(ProviderAdmission.tryEnter());
            }
        }finally{worker.shutdownNow();}
        free();
    }
    @Test void logoutCanProceedWhileTheHeavySlotIsHeld() throws Exception {
        String path = "/api/account";
        var body = ModelGateway.JSON.createObjectNode().put("action","logout").put("expectedEmail","user@example.test");
        try (var heavy = ProviderAdmission.tryEnter()) {
            assertNotNull(heavy);
            try (var logout = HeavyRequestBody.read(request(path,body.toString()),16384,path)) {
                assertArrayEquals(body.toString().getBytes(StandardCharsets.UTF_8),logout.bytes);
            }
            for (String invalid : List.of(body.deepCopy().put("extra","x").toString(),
                    body.deepCopy().put("expectedEmail","x".repeat(255)).toString(),
                    body.deepCopy().put("expectedEmail",123).toString())) {
                assertEquals("MEMORY_BUSY",assertThrows(StorageFailure.class,
                    () -> HeavyRequestBody.read(request(path,invalid),16384,path)).code);
            }
            assertNull(ProviderAdmission.tryEnter());
        }
    }
    @Test void boundedSessionActivityDoesNotCompeteWithLargeBodies() throws Exception {
        String path = "/api/account";
        var activity = ModelGateway.JSON.createObjectNode().put("action","userActivity")
            .put("expectedEmail","user@example.test").put("activityAgeMs",0);
        try (var heavy = ProviderAdmission.tryEnter()) {
            assertNotNull(heavy);
            for (int age : List.of(0, AccountIdleSessions.MAX_ACTIVITY_AGE_MILLIS)) {
                String json = activity.put("activityAgeMs",age).toString();
                try (var report = HeavyRequestBody.read(request(path,json),16384,path)) {
                    assertArrayEquals(json.getBytes(StandardCharsets.UTF_8),report.bytes);
                }
            }
            for (var invalid : List.of(activity.deepCopy().put("extra",true),
                    activity.deepCopy().put("expectedEmail","x".repeat(255)),
                    activity.deepCopy().put("activityAgeMs",-1), activity.deepCopy().put("activityAgeMs",5001),
                    activity.deepCopy().put("activityAgeMs",4294967296L), activity.deepCopy().put("activityAgeMs","1"))) {
                assertEquals("MEMORY_BUSY",assertThrows(StorageFailure.class,
                    () -> HeavyRequestBody.read(request(path,invalid.toString()),16384,path)).code);
            }
            assertNull(ProviderAdmission.tryEnter());
        }
    }
    @Test void boundedLoginUsesItsOwnSingleSlotWhileHeavyWorkRemainsExclusive()throws Exception {
        var heavy=ProviderAdmission.tryEnter();assertNotNull(heavy);
        try{
            String body=login(UUID.randomUUID().toString());var first=HeavyRequestBody.read(request("/api/account",body),6*1024*1024,"/api/account");
            assertNull(ProviderAdmission.tryEnter());
            var busy=assertThrows(StorageFailure.class,()->HeavyRequestBody.read(request("/api/account",body),6*1024*1024,"/api/account"));assertEquals(429,busy.status);assertEquals("ACCOUNT_BUSY",busy.code);
            first.close();first.close();try(var next=HeavyRequestBody.read(request("/api/account",body),6*1024*1024,"/api/account")){assertEquals(body.getBytes(StandardCharsets.UTF_8).length,next.bytes.length);assertEquals(429,assertThrows(StorageFailure.class,()->HeavyRequestBody.read(request("/api/account",body),6*1024*1024,"/api/account")).status);}
            assertNull(ProviderAdmission.tryEnter());
        }finally{heavy.close();}free();
    }
    @Test void extraUnknownOversizedMalformedDuplicateAndTruncatedBodiesCannotClaimTheSmallAuthSlot()throws Exception {
        var heavy=ProviderAdmission.tryEnter();assertNotNull(heavy);String exact=login(UUID.randomUUID().toString());
        try{
            var value=(com.fasterxml.jackson.databind.node.ObjectNode)ModelGateway.JSON.readTree(exact);
            for(String body:List.of(value.deepCopy().put("data","extra field").toString(),value.deepCopy().put("action","keys").toString(),value.deepCopy().put("password","x".repeat(257)).toString(),value.deepCopy().put("email","x".repeat(255)).toString(),value.deepCopy().put("operationId","invalid-id").toString(),exact+" ".repeat(HeavyRequestBody.SMALL_LIMIT),exact.substring(0,exact.length()-1),exact+"{}",exact.substring(0,1)+"\"action\":\"login\","+exact.substring(1))){
                var refusal=assertThrows(StorageFailure.class,()->HeavyRequestBody.read(request("/api/account",body),6*1024*1024,"/api/account"));assertEquals("MEMORY_BUSY",refusal.code);
                try(var valid=HeavyRequestBody.read(request("/api/account",exact),6*1024*1024,"/api/account")){assertTrue(valid.bytes.length<HeavyRequestBody.SMALL_LIMIT);}
            }
            var timeout=request("/api/account",new InputStream(){public int read()throws IOException{throw new SocketTimeoutException("Synthetic truncated body timeout");}});assertThrows(SocketTimeoutException.class,()->HeavyRequestBody.read(timeout,6*1024*1024,"/api/account"));
            try(var valid=HeavyRequestBody.read(request("/api/account",exact),6*1024*1024,"/api/account")){assertNotNull(valid);}
        }finally{heavy.close();}free();
    }
    @Test void declaredContentLengthCannotHideTrailingBytesOrOversizedBodyAndMalformedRequestDoesNotLeakSlots()throws Exception {
        String exact=login(UUID.randomUUID().toString());var heavy=ProviderAdmission.tryEnter();assertNotNull(heavy);
        try{
            var request=request("/api/account",exact+"{} ");when(request.getContentLength()).thenReturn(1);when(request.getContentLengthLong()).thenReturn(1L);
            assertEquals("MEMORY_BUSY",assertThrows(StorageFailure.class,()->HeavyRequestBody.read(request,6*1024*1024,"/api/account")).code);
            var large=request("/api/account",exact+" ".repeat(HeavyRequestBody.SMALL_LIMIT));when(large.getContentLength()).thenReturn(0);when(large.getContentLengthLong()).thenReturn(0L);
            assertEquals("MEMORY_BUSY",assertThrows(StorageFailure.class,()->HeavyRequestBody.read(large,6*1024*1024,"/api/account")).code);
        }finally{heavy.close();}
        var malformed=new AtomicTransactionsTest.Capture();AtomicHttp.run(request("/api/account",exact.substring(0,exact.length()-1)),malformed.response,(a,b)->fail("Malformed login reached handler"),null,new Properties(),()->{},(c,n)->fail("Malformed login reached SQL"));assertEquals(400,malformed.status.get());free();
        try(var auth=HeavyRequestBody.read(request("/api/account",exact),6*1024*1024,"/api/account")){assertNotNull(auth);}free();
    }
    @Test void loginAttemptFailureAndLostConfirmationKeepOneDurableCountUnderHeavySlotContention()throws Exception {
        var heavy=ProviderAdmission.tryEnter();assertNotNull(heavy);
        java.nio.file.Path config=java.nio.file.Files.createTempFile("starhub-small-auth-test-",".properties");String previous=System.getProperty("starhub.account.config");
        try(var f=new AtomicTransactionsTest.Fixture()){
            try(OutputStream out=java.nio.file.Files.newOutputStream(config)){f.p.store(out,"Synthetic local-only configuration");}System.setProperty("starhub.account.config",config.toString());
            String operation=UUID.randomUUID().toString(),body=login(operation);FilterChain login=(req,res)->new AccountServlet().doPost((HttpServletRequest)req,(HttpServletResponse)res);
            f.rejectConfirm.set(true);var unknown=new AtomicTransactionsTest.Capture();var first=request("/api/account",body);when(first.getRemoteAddr()).thenReturn("192.0.2.119");AtomicHttp.run(first,unknown.response,login,f.db,f.p,()->{},f.files::transientCapacity);assertEquals(503,unknown.status.get());assertEquals(1,f.count("SELECT failures FROM starhub_login_limits"));
            f.rejectConfirm.set(false);var known=new AtomicTransactionsTest.Capture();var retry=request("/api/account",body);when(retry.getRemoteAddr()).thenReturn("192.0.2.119");AtomicHttp.run(retry,known.response,login,f.db,f.p,()->{},f.files::transientCapacity);assertEquals(401,known.status.get());verify(known.response).setHeader("X-Four-Project-Durability","dual");assertEquals(1,f.count("SELECT failures FROM starhub_login_limits"));assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_login_attempts"));assertTrue(known.bytes.toString(StandardCharsets.UTF_8).contains(operation));
            try(var available=HeavyRequestBody.read(request("/api/account",body),6*1024*1024,"/api/account")){assertNotNull(available);}assertNull(ProviderAdmission.tryEnter());
        }finally{if(previous==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",previous);java.nio.file.Files.deleteIfExists(config);heavy.close();}free();
    }
    @Test void providerGuestAndWorkspaceShareTheSlotBeforeLargeBodyOrSessionOrSql()throws Exception {
        var held=ProviderAdmission.tryEnter();assertNotNull(held);
        try{
            var count=new AtomicInteger();var big=request("/api/workspace",new InputStream(){public int read(){count.incrementAndGet();return 'x';}});var output=new AtomicTransactionsTest.Capture();
            AtomicHttp.run(big,output.response,(a,b)->fail("Busy workspace reached SQL/handler"),null,new Properties(),()->{},(c,n)->fail("Busy request reached physical check"));assertEquals(429,output.status.get());assertEquals(HeavyRequestBody.SMALL_LIMIT+1,count.get());verify(big,never()).getSession(anyBoolean());
            var guest=request("/api/guest-session","{\"action\":\"get\"}");var blocked=new AtomicTransactionsTest.Capture();new GuestSessionServlet().doPost(guest,blocked.response);assertEquals(429,blocked.status.get());verify(guest,never()).getSession(anyBoolean());assertFalse(blocked.bytes.toString(StandardCharsets.UTF_8).contains("COMMITTED"));
            for(String action:List.of("readFile","requestStatus","operationStatus","listFiles","listChats","saveChat","deleteFile"))assertEquals(429,assertThrows(StorageFailure.class,()->HeavyRequestBody.read(request("/api/workspace","{\"action\":\""+action+"\"}"),6*1024*1024,"/api/workspace")).status);
        }finally{held.close();}free();
    }
    @Test void onlyStrictSmallSummaryOrLoginStatusCanBypassAndCannotSmugglePayload()throws Exception {
        var held=ProviderAdmission.tryEnter();assertNotNull(held);
        try{
            for(String path:List.of("/api/workspace","/api/account")){String action=path.endsWith("workspace")?"operationSummary":"loginStatus";try(var small=HeavyRequestBody.read(request(path,"{\"action\":\""+action+"\",\"operationId\":\""+UUID.randomUUID()+"\"}"),6*1024*1024,path)){assertTrue(small.bytes.length<1024);}assertEquals(429,assertThrows(StorageFailure.class,()->HeavyRequestBody.read(request(path,"{\"action\":\""+action+"\",\"data\":\"not a status field\"}"),6*1024*1024,path)).status);}
            String tooBig="{\"action\":\"operationSummary\",\"operationId\":\""+"x".repeat(HeavyRequestBody.SMALL_LIMIT)+"\"}";assertEquals(429,assertThrows(StorageFailure.class,()->HeavyRequestBody.read(request("/api/workspace",tooBig),6*1024*1024,"/api/workspace")).status);
            for(String action:List.of("get","status","snapshot","renew"))assertEquals(429,assertThrows(StorageFailure.class,()->HeavyRequestBody.read(request("/api/guest-session","{\"action\":\""+action+"\"}"),1024*1024,"/api/guest-session")).status);
        }finally{held.close();}free();
    }
    @Test void bodyTimeoutSizeRejectionAndHandlerDisconnectAlwaysReleaseSharedSlot()throws Exception {
        var seen=new AtomicInteger();var timeout=request("/api/workspace",new InputStream(){public int read()throws IOException{if(seen.incrementAndGet()<=HeavyRequestBody.SMALL_LIMIT+1)return 'x';throw new SocketTimeoutException("Synthetic body timeout");}});assertThrows(SocketTimeoutException.class,()->HeavyRequestBody.read(timeout,6*1024*1024,"/api/workspace"));free();
        assertEquals(413,assertThrows(StorageFailure.class,()->HeavyRequestBody.read(request("/api/guest-session","x".repeat(1024*1024+1)),1024*1024,"/api/guest-session")).status);free();
        try(var f=new AtomicTransactionsTest.Fixture()){
            var out=new AtomicTransactionsTest.Capture();AtomicHttp.run(request("/api/workspace","{\"action\":\"readFile\"}"),out.response,(a,b)->{throw new IOException("Synthetic response disconnect");},f.db,f.p,()->{},f.files::transientCapacity);assertEquals(503,out.status.get());free();
        }
    }
    @Test void summaryRemainsSmallAndReadsOnlyOwnedCurrentGenerationState()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            String op=UUID.randomUUID().toString();byte[] data={1};var metadata=ModelGateway.JSON.createArrayNode();metadata.addObject().put("name","a.txt").put("mime","text/plain").put("size",1).put("sha256",Base64.getEncoder().encodeToString(FileStorage.hash(data)));f.files.reserve(1,op,metadata,1);
            var result=f.files.summary(1,op);assertEquals(Set.of("operationId","state","kind","logicalBytes"),new HashSet<String>(){{result.fieldNames().forEachRemaining(this::add);}});assertEquals("RESERVED",result.path("state").asText());assertTrue(ModelGateway.JSON.writeValueAsBytes(result).length<512);
            try(var c=f.connection();var q=c.prepareStatement("UPDATE users SET generation=? WHERE id=1")){q.setString(1,UUID.randomUUID().toString());q.executeUpdate();}assertEquals(503,assertThrows(StorageFailure.class,()->f.files.summary(1,op)).status);
        }
    }
}
