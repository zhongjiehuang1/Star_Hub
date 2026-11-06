package com.starhub;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.websocket.*;
import javax.websocket.server.*;
import javax.servlet.*;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpSession;

@ServerEndpoint(value="/realtime",configurator=RealtimeEndpoint.Origin.class)
public final class RealtimeEndpoint {
    private static final Set<RealtimeEndpoint> ACTIVE=ConcurrentHashMap.newKeySet();
    private static final Semaphore SLOTS=new Semaphore(4);
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"starhub-realtime-timeout");t.setDaemon(true);return t;});
    private static final ExecutorService EXECUTOR=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"starhub-realtime-io");t.setDaemon(true);return t;});
    private static final HttpClient CLIENT=HttpClient.newBuilder().executor(EXECUTOR).connectTimeout(Duration.ofSeconds(12)).build();
    private RealtimeBridge bridge;private ScheduledFuture<?> timeout;private boolean acquired;
    public static class Origin extends ServerEndpointConfig.Configurator {
        public boolean checkOrigin(String origin){return System.getenv().getOrDefault("STARHUB_PUBLIC_ORIGIN","https://starhub.zhongjiehuang.com").equals(origin);}
        public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
            List<String> ips = request.getHeaders().get("X-Real-IP");
            String country = GeoCountry.lookup(ips != null && ips.size() == 1 ? ips.get(0) : null);
            HttpSession session = request.getHttpSession() instanceof HttpSession ? (HttpSession) request.getHttpSession() : null;
            config.getUserProperties().put("regionAccessAllowed", RegionAccess.allowed(country, session));
        }
    }
    @OnOpen public synchronized void open(Session session, EndpointConfig config)throws Exception{
        if (!Boolean.TRUE.equals(config.getUserProperties().get("regionAccessAllowed"))) { session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Tester verification required")); return; }
        if(!SLOTS.tryAcquire()){session.close(new CloseReason(CloseReason.CloseCodes.TRY_AGAIN_LATER,"Busy"));return;}
        acquired=true;ACTIVE.add(this);session.setMaxTextMessageBufferSize(6*1024*1024);session.setMaxIdleTimeout(130000);session.getAsyncRemote().setSendTimeout(15000);
        bridge=new RealtimeBridge(new RealtimeBridge.Downstream(){
            public CompletionStage<Void> send(String text){CompletableFuture<Void> result=new CompletableFuture<>();if(!session.isOpen()){result.completeExceptionally(new IllegalStateException());return result;}session.getAsyncRemote().sendText(text,r->{if(r.isOK())result.complete(null);else result.completeExceptionally(r.getException());});return result;}
            public void close(){try{session.close();}catch(Exception ignored){}release();}
        },RealtimeBridge.network(CLIENT));
        timeout=TIMER.scheduleAtFixedRate(()->{if(bridge.expired())bridge.fail("实时会话超时，请重新连接。");},10,10,TimeUnit.SECONDS);
    }
    @OnMessage public void message(String text){if(bridge!=null)bridge.receive(text);}
    @OnClose public void close(){if(bridge!=null)bridge.close();release();}
    @OnError public void error(Throwable ignored){if(bridge!=null)bridge.close();release();}
    private synchronized void release(){if(timeout!=null)timeout.cancel(false);ACTIVE.remove(this);if(acquired){acquired=false;SLOTS.release();}}
    @WebListener public static class Lifecycle implements ServletContextListener {
        public void contextDestroyed(ServletContextEvent event){for(RealtimeEndpoint e:ACTIVE)e.close();TIMER.shutdownNow();EXECUTOR.shutdownNow();}
    }
}
