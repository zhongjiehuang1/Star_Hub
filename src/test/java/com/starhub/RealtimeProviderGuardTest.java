package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Fake provider socket plus real H2 claim/SENT/result transactions. No external service is called. */
class RealtimeProviderGuardTest {
    static final class Guard implements RealtimeBridge.Operation {
        final ProviderOperationsTest.Harness h;ProviderOperations.Ticket ticket;String id;boolean ended;
        Guard(ProviderOperationsTest.Harness h){this.h=h;}
        public void start(JsonNode input)throws Exception{id=input.path("operationId").asText();var claim=h.operations.claim(h.owner,id,"test:live-session",input);if(!claim.state().equals("CLAIMED"))throw ProviderOperations.pending(id);ticket=h.operations.dispatch(h.owner,id,"test:live-session",input);}
        public AutoCloseable enter(){return ticket.enter();}public void verify()throws Exception{if(ticket!=null)ticket.beforeNetwork();}public String id(){return id;}public String durability(){return ticket==null?null:ticket.lease.mode();}
        public void end(boolean unknown){ended=true;if(ticket!=null)try{if(unknown)ticket.unknown();else ticket.complete(200,ModelGateway.JSON.createObjectNode().put("relayClosed",true).put("audioRetained",false));}catch(Exception failure){ticket.unknown();}}
    }
    static String connect(String operation){return ModelGateway.JSON.createObjectNode().put("type","starhub.connect").put("operationId",operation).put("ownerTab","a".repeat(32)).put("provider","openai").put("model","gpt-realtime-2.1").put("apiKey","test-only-no-real-api-key").toString();}
    static RealtimeBridge.Downstream output(List<String> messages){return new RealtimeBridge.Downstream(){public CompletionStage<Void> send(String text){messages.add(text);return CompletableFuture.completedFuture(null);}public void close(){}};}
    @Test void onlyOneProviderSocketCanOpenForTheSameDurableLiveSessionId()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            String id=UUID.randomUUID().toString();AtomicInteger opens=new AtomicInteger();AtomicReference<Consumer<String>> upstream=new AtomicReference<>();List<String> messages=new ArrayList<>();Guard firstGuard=new Guard(h);
            RealtimeBridge.Connector provider=(model,key,incoming,closed,failed)->{try{ProviderOperations.beforeExternal();}catch(Exception denied){return CompletableFuture.failedFuture(denied);}opens.incrementAndGet();upstream.set(incoming);return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String text){return CompletableFuture.completedFuture(null);}public void close(){}});};
            RealtimeBridge first=new RealtimeBridge(output(messages),provider,firstGuard);first.receive(connect(id));assertEquals(1,opens.get());assertEquals("SENT",h.operations.status(h.owner,id).state());upstream.get().accept("{\"type\":\"session.updated\"}");assertTrue(messages.stream().anyMatch(text->text.contains("starhub.ready")&&text.contains(id)&&text.contains("SENT")));
            RealtimeBridge retry=new RealtimeBridge(output(new ArrayList<>()),provider,new Guard(h));retry.receive(connect(id));assertEquals(1,opens.get());
            first.close(false);assertEquals("COMPLETED",h.operations.status(h.owner,id).state());assertFalse(h.operations.status(h.owner,id).result().path("audioRetained").asBoolean(true));
            new RealtimeBridge(output(new ArrayList<>()),provider,new Guard(h)).receive(connect(id));assertEquals(1,opens.get());assertEquals(0,h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));
        }
    }
    @Test void changedGrantClosesTheLiveRelayAndKeepsItsUncertainReceiptWithoutReplayingAudio()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            String id=UUID.randomUUID().toString();Guard guard=new Guard(h);AtomicInteger opens=new AtomicInteger(),sent=new AtomicInteger();AtomicBoolean closed=new AtomicBoolean();
            RealtimeBridge bridge=new RealtimeBridge(output(new ArrayList<>()),(model,key,receive,ended,failed)->{opens.incrementAndGet();return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String text){sent.incrementAndGet();return CompletableFuture.completedFuture(null);}public void close(){closed.set(true);}});},guard);
            bridge.receive(connect(id));assertEquals(1,sent.get());h.f.changePermit.set(true);assertTrue(bridge.expired());assertTrue(closed.get());assertTrue(guard.ended);h.f.changePermit.set(false);
            assertTrue(Set.of("SENT","UNKNOWN").contains(h.operations.status(h.owner,id).state()));int before=sent.get();bridge.receive("{\"type\":\"input_audio_buffer.append\",\"audio\":\"AAA=\"}");assertEquals(before,sent.get());assertEquals(1,opens.get());assertTrue(h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals")>0);
        }
    }
    @Test void queuedControlRechecksAuthorityAtActualDispatchAfterEarlierAudioCompletes()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            String id=UUID.randomUUID().toString();AtomicInteger sends=new AtomicInteger();AtomicBoolean closed=new AtomicBoolean();AtomicReference<Consumer<String>> incoming=new AtomicReference<>();CompletableFuture<Void> first=new CompletableFuture<>();List<String> messages=new ArrayList<>();
            RealtimeBridge bridge=new RealtimeBridge(output(messages),(model,key,receive,ended,failed)->{incoming.set(receive);return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String text){sends.incrementAndGet();return first;}public void close(){closed.set(true);}});},new Guard(h));
            bridge.receive(connect(id));assertEquals(1,sends.get());incoming.get().accept("{\"type\":\"session.updated\"}");bridge.receive("{\"type\":\"input_audio_buffer.commit\"}");assertEquals(1,sends.get());
            h.f.changePermit.set(true);first.complete(null);assertEquals(1,sends.get());assertTrue(closed.get());assertTrue(messages.stream().anyMatch(text->text.contains("UNKNOWN")));h.f.changePermit.set(false);assertEquals("SENT",h.operations.status(h.owner,id).state());
        }
    }
}
