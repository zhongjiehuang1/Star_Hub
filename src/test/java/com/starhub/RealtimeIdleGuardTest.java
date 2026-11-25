package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeIdleGuardTest {
    @Test void idleExpiryStopsNewInputAndStopsDeliveringTheAlreadyStartedReply()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            var original=new RealtimeProviderGuardTest.Guard(h);var denyAudio=new AtomicBoolean();
            RealtimeBridge.Operation operation=new RealtimeBridge.Operation(){
                public void start(JsonNode input)throws Exception{original.start(input);}public AutoCloseable enter(){return original.enter();}
                public void verify()throws Exception{original.verify();}public void verifyAudio(){if(denyAudio.get())throw new StorageFailure(401,"SESSION_ENDED","ended");}
                public void end(boolean uncertain){original.end(uncertain);}public String id(){return original.id();}public String durability(){return original.durability();}
            };
            AtomicInteger sent=new AtomicInteger();AtomicReference<Consumer<String>> incoming=new AtomicReference<>();List<String> messages=new ArrayList<>();
            var bridge=new RealtimeBridge(RealtimeProviderGuardTest.output(messages),(model,key,recv,closed,error)->{
                incoming.set(recv);return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String text){sent.incrementAndGet();return CompletableFuture.completedFuture(null);}public void close(){}});
            },operation);
            bridge.receive(RealtimeProviderGuardTest.connect(UUID.randomUUID().toString()));incoming.get().accept("{\"type\":\"session.updated\"}");
            incoming.get().accept("{\"type\":\"response.created\",\"response\":{\"id\":\"reply_1\"}}");
            denyAudio.set(true);bridge.receive("{\"type\":\"input_audio_buffer.append\",\"audio\":\"AAA=\"}");
            assertEquals(1,sent.get());assertFalse(original.ended);assertTrue(messages.stream().anyMatch(s->s.contains("starhub.session_ended")));
            incoming.get().accept("{\"type\":\"response.output_text.delta\",\"response_id\":\"reply_1\",\"delta\":\"original output\"}");
            incoming.get().accept("{\"type\":\"response.done\",\"response\":{\"id\":\"reply_1\",\"status\":\"completed\"}}");
            assertFalse(messages.stream().anyMatch(s->s.contains("original output")));assertFalse(messages.stream().anyMatch(s->s.contains("response.done")));assertTrue(original.ended);
            bridge.receive("{\"type\":\"input_audio_buffer.append\",\"audio\":\"AAA=\"}");assertEquals(1,sent.get());
            bridge.close(false);
        }
    }
    @Test void queuedAudioIsRejectedAtDispatchWhenItsIdleGrantExpires()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            var original=new RealtimeProviderGuardTest.Guard(h);var denyAudio=new AtomicBoolean();
            RealtimeBridge.Operation operation=new RealtimeBridge.Operation(){
                public void start(JsonNode input)throws Exception{original.start(input);}public AutoCloseable enter(){return original.enter();}
                public void verify()throws Exception{original.verify();}public void verifyAudio(){if(denyAudio.get())throw new StorageFailure(401,"SESSION_ENDED","ended");}
                public void end(boolean uncertain){original.end(uncertain);}public String id(){return original.id();}public String durability(){return original.durability();}
            };
            AtomicInteger sent=new AtomicInteger();AtomicReference<Consumer<String>> incoming=new AtomicReference<>();CompletableFuture<Void> first=new CompletableFuture<>();
            var bridge=new RealtimeBridge(RealtimeProviderGuardTest.output(new ArrayList<>()),(model,key,recv,closed,error)->{
                incoming.set(recv);return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String text){sent.incrementAndGet();return first;}public void close(){}});
            },operation);
            bridge.receive(RealtimeProviderGuardTest.connect(UUID.randomUUID().toString()));assertEquals(1,sent.get());
            incoming.get().accept("{\"type\":\"session.updated\"}");bridge.receive("{\"type\":\"input_audio_buffer.append\",\"audio\":\"AAA=\"}");
            denyAudio.set(true);first.complete(null);assertEquals(1,sent.get());assertTrue(original.ended);
        }
    }
}
