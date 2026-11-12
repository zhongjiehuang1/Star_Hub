package com.starhub;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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
