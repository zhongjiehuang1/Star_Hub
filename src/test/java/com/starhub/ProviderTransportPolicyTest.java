package com.starhub;

import java.io.*;
import java.net.*;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real local sockets only: no official provider URL, key or paid request. */
class ProviderTransportPolicyTest {
    static final class Server implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0,8,InetAddress.getLoopbackAddress());
        final AtomicInteger requests=new AtomicInteger();final List<String> methods=new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();final Thread accept;final String response;final long bodyPause;
        volatile boolean closed;
        Server(String response,long bodyPause)throws Exception {
            this.response=response;this.bodyPause=bodyPause;socket.setSoTimeout(100);
            accept=new Thread(()->{while(!closed)try(Socket client=socket.accept()){
                client.setSoTimeout(3000);InputStream in=client.getInputStream();StringBuilder headers=new StringBuilder();int b;
                while((b=in.read())>=0){headers.append((char)b);if(headers.toString().endsWith("\r\n\r\n"))break;if(headers.length()>65536)throw new IOException("Test header limit");}
                if(headers.isEmpty())continue;methods.add(headers.substring(0,headers.indexOf(" ")));int length=0;
                for(String line:headers.toString().split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                if(in.readNBytes(length).length!=length)throw new IOException("Incomplete synthetic body");requests.incrementAndGet();
                if(response!=null){client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();if(bodyPause>0)Thread.sleep(bodyPause);}
            }catch(SocketTimeoutException ignored){}catch(Exception problem){if(!closed)failure.compareAndSet(null,problem);}},"synthetic-provider-transport");accept.setDaemon(true);accept.start();
        }
        URI uri(String scheme){return URI.create(scheme+"://127.0.0.1:"+socket.getLocalPort()+"/test-only");}
        public void close()throws Exception {closed=true;socket.close();accept.interrupt();accept.join(1500);if(failure.get()!=null)throw new AssertionError("Synthetic transport failed",failure.get());}
    }
    @Test void startupFlagsCannotBeReplacedByLatePropertiesOrUnsafeLogging(){
        Properties safe=new Properties();safe.putAll(ProviderTransportPolicy.FLAGS);List<String> flags=ProviderTransportPolicy.FLAGS.entrySet().stream().map(f->"-D"+f.getKey()+"="+f.getValue()).toList();
        assertTrue(ProviderTransportPolicy.valid(flags,safe));assertFalse(ProviderTransportPolicy.valid(List.of(),safe));
        List<String> overridden=new ArrayList<>(flags);overridden.add("-Djdk.httpclient.redirects.retrylimit=5");assertFalse(ProviderTransportPolicy.valid(overridden,safe));
        safe.setProperty("jdk.httpclient.HttpClient.log","headers,content");assertFalse(ProviderTransportPolicy.valid(flags,safe));
        assertDoesNotThrow(ProviderTransportPolicy::require);
    }
    @Test void fixedLengthPostWithLostResponseIsSentOnlyOnce()throws Exception {
        try(Server server=new Server(null,0)){
            HttpURLConnection request=(HttpURLConnection)server.uri("http").toURL().openConnection();request.setRequestMethod("POST");request.setConnectTimeout(2000);request.setReadTimeout(2000);request.setInstanceFollowRedirects(false);
            try{ProviderTransportPolicy.writeBody(request,"synthetic-effect".getBytes(StandardCharsets.UTF_8));assertThrows(IOException.class,request::getResponseCode);}finally{request.disconnect();}
            assertEquals(1,server.requests.get());assertEquals(List.of("POST"),server.methods);
        }
    }
    @Test void lostGetResponseAndWebsocketHandshakeAreNotTransparentlyRetried()throws Exception {
        try(Server get=new Server(null,0)){
            assertThrows(IOException.class,()->ProviderTransportPolicy.get(get.uri("http"),Map.of(),2,128));assertEquals(1,get.requests.get());assertEquals(List.of("GET"),get.methods);
        }
        try(Server ws=new Server(null,0)){
            CompletableFuture<WebSocket> attempt=ProviderTransportPolicy.client().newWebSocketBuilder().buildAsync(ws.uri("ws"),new WebSocket.Listener(){});
            assertThrows(ExecutionException.class,()->attempt.get(3,TimeUnit.SECONDS));assertEquals(1,ws.requests.get());assertEquals(List.of("GET"),ws.methods);
        }
    }
    @Test void redirectsAreReturnedAndOversizeOrStalledBodiesAreBounded()throws Exception {
        try(Server redirect=new Server("HTTP/1.1 307 Temporary Redirect\r\nLocation: /repeat\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",0)){
            assertEquals(307,ProviderTransportPolicy.get(redirect.uri("http"),Map.of(),2,128).status());assertEquals(1,redirect.requests.get());
        }
        try(Server oversized=new Server("HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\n12345678",0)){
            assertThrows(IOException.class,()->ProviderTransportPolicy.get(oversized.uri("http"),Map.of(),2,4));assertEquals(1,oversized.requests.get());
        }
        try(Server stalled=new Server("HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\n",3000)){
            assertThrows(SocketTimeoutException.class,()->ProviderTransportPolicy.get(stalled.uri("http"),Map.of(),1,16));assertEquals(1,stalled.requests.get());
        }
    }
}
