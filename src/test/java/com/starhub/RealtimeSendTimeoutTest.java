package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeSendTimeoutTest {
    @Test void stuckUpstreamSendEndsOnceWithoutReplayingQueuedAudio() throws Exception {
        List<String> sent=Collections.synchronizedList(new ArrayList<>()),down=Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Consumer<String>> incoming=new AtomicReference<>();AtomicReference<Boolean> uncertain=new AtomicReference<>();
        AtomicBoolean upstreamClosed=new AtomicBoolean(),downstreamClosed=new AtomicBoolean();CountDownLatch ended=new CountDownLatch(1);
        RealtimeBridge.Operation op=new RealtimeBridge.Operation(){
            public void start(JsonNode n){}public AutoCloseable enter(){return ()->{};}public void verify(){}
            public void end(boolean u){uncertain.set(u);ended.countDown();}public String id(){return null;}public String durability(){return null;}
        };
        CompletableFuture<Void> stuck=new CompletableFuture<>();
        RealtimeBridge.Downstream output=new RealtimeBridge.Downstream(){public CompletionStage<Void> send(String s){down.add(s);return CompletableFuture.completedFuture(null);}public void close(){downstreamClosed.set(true);}};
        RealtimeBridge.Connector connector=(model,key,receive,closed,failed)->{
            incoming.set(receive);
            return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){
                public CompletionStage<Void> send(String s){sent.add(s);return s.contains("session.input_audio.append")?stuck:CompletableFuture.completedFuture(null);}
                public void close(){upstreamClosed.set(true);throw new IllegalStateException("simulated close failure");}
            });
        };
        RealtimeBridge bridge=new RealtimeBridge(output,connector,op,(key,query,zone)->null,Runnable::run,50);
        bridge.receive("{\"type\":\"starhub.connect\",\"provider\":\"openai\",\"model\":\"gpt-live-1\",\"apiKey\":\"test-key\"}");
        incoming.get().accept("{\"type\":\"session.started\"}");
        bridge.receive("{\"type\":\"input_audio_buffer.append\",\"audio\":\"AAA=\"}");
        bridge.receive("{\"type\":\"input_audio_buffer.append\",\"audio\":\"AAA=\"}");
        assertTrue(ended.await(2,TimeUnit.SECONDS));assertEquals(Boolean.TRUE,uncertain.get());
        assertTrue(upstreamClosed.get());assertTrue(downstreamClosed.get());
        assertEquals(2,sent.size(),"the queued audio and close command must not be replayed into a failed output path");
        assertEquals(1,down.stream().filter(s->s.contains("语音上行连接超时或中断")).count());
        bridge.receive("{\"type\":\"input_audio_buffer.append\",\"audio\":\"AAA=\"}");assertEquals(2,sent.size());
    }

    @Test void liveBackendConfigurationErrorExposesOnlyAllowlistedCode() throws Exception {
        List<String> down=new ArrayList<>();AtomicReference<Consumer<String>> incoming=new AtomicReference<>();
        RealtimeBridge bridge=new RealtimeBridge(new RealtimeBridge.Downstream(){public CompletionStage<Void> send(String s){down.add(s);return CompletableFuture.completedFuture(null);}public void close(){}},
            (model,key,receive,closed,failed)->{incoming.set(receive);return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String s){return CompletableFuture.completedFuture(null);}public void close(){}});});
        bridge.receive("{\"type\":\"starhub.connect\",\"provider\":\"openai\",\"model\":\"gpt-live-1\",\"apiKey\":\"test-key\"}");incoming.get().accept("{\"type\":\"session.started\"}");
        incoming.get().accept("{\"type\":\"error\",\"error\":{\"code\":\"responses_validation_failed\"}}");
        assertEquals("responses_validation_failed",ModelGateway.JSON.readTree(down.get(down.size()-1)).path("errorCode").asText());
        incoming.get().accept("{\"type\":\"error\",\"error\":{\"code\":\"other_code\"}}");assertFalse(ModelGateway.JSON.readTree(down.get(down.size()-1)).has("errorCode"));bridge.close();
    }
}
