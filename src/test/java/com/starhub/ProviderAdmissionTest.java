package com.starhub;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercise actual servlet admission before parsing; no provider or database call. */
class ProviderAdmissionTest {
    private HttpServletRequest request(String path,InputStream input)throws Exception {
        var request=mock(HttpServletRequest.class);when(request.getPathInfo()).thenReturn(path);when(request.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(request.getHeader("X-Starhub-Client")).thenReturn("web");when(request.getContentType()).thenReturn("application/json");when(request.getInputStream()).thenReturn(new ServletInputStream(){public boolean isFinished(){return false;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}public int read()throws IOException{return input.read();}});return request;
    }
    private static final class Response {
        final HttpServletResponse response=mock(HttpServletResponse.class);final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Response()throws Exception{when(response.getOutputStream()).thenReturn(new ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){}public void write(int value){bytes.write(value);}});}
    }
    private void available(){var permit=ProviderAdmission.tryEnter();assertNotNull(permit,"A finished request leaked the heavy-task slot");permit.close();}
    @Test void allPublicPathsAndDifferentServletInstancesShareOneSlotBeforeReadingBodies()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        var first=request("/run",new InputStream(){boolean returned;public int read()throws IOException{if(returned)return -1;returned=true;entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Synthetic timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Synthetic interruption");}return '{';}});var firstResponse=new Response();
        try{
            Future<?> active=pool.submit(()->{try{new ApiServlet().doPost(first,firstResponse.response);}catch(IOException e){throw new UncheckedIOException(e);}});assertTrue(entered.await(5,TimeUnit.SECONDS));
            for(String path:List.of("/run","/chat","/check","/batch","/provider-operation","/provider-media")){
                var blocked=request(path,new ByteArrayInputStream("{\"operationId\":\"synthetic-unread\"}".getBytes(StandardCharsets.UTF_8)));var output=new Response();new ApiServlet().doPost(blocked,output.response);
                verify(output.response).setStatus(429);verify(output.response).setHeader("Retry-After","1");verify(blocked,never()).getInputStream();verify(blocked,never()).getSession(anyBoolean());String json=output.bytes.toString(StandardCharsets.UTF_8);assertTrue(json.contains("PROVIDER_BUSY"));assertFalse(json.contains("COMPLETED"));assertFalse(json.contains("COMMITTED"));assertFalse(json.contains("SENT"));
            }
            release.countDown();active.get(5,TimeUnit.SECONDS);available();
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void bodyTimeoutAndSseDisconnectedErrorResponseBothReleaseTheActualServletSlot()throws Exception {
        InputStream timedOut=new InputStream(){public int read()throws IOException{throw new SocketTimeoutException("Synthetic body timeout");}};
        var output=new Response();new ApiServlet().doPost(request("/provider-operation",timedOut),output.response);verify(output.response).setStatus(503);available();
        var disconnected=mock(HttpServletResponse.class);when(disconnected.isCommitted()).thenReturn(true);when(disconnected.getContentType()).thenReturn("text/event-stream;charset=UTF-8");when(disconnected.getOutputStream()).thenReturn(new ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){}public void write(int value)throws IOException{throw new EOFException("Synthetic SSE peer closed");}});
        assertThrows(IOException.class,()->new ApiServlet().doPost(request("/chat",timedOut),disconnected));available();
    }
    @Test void rejectedOriginsDoNotTakeTheSlotAndDoubleCloseCannotIncreaseCapacity()throws Exception {
        var request=request("/run",new ByteArrayInputStream(new byte[0]));when(request.getHeader("Origin")).thenReturn("https://invalid.example.test");var response=new Response();new ApiServlet().doPost(request,response.response);verify(response.response).setStatus(403);available();
        var lease=ProviderAdmission.tryEnter();assertNotNull(lease);lease.close();lease.close();var only=ProviderAdmission.tryEnter();assertNotNull(only);assertNull(ProviderAdmission.tryEnter());only.close();available();
    }
}
