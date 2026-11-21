package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeWebSearchTest {
    static final class Fixture {
        final List<String> up=new ArrayList<>(),down=new ArrayList<>();final List<Runnable> tasks=new ArrayList<>();
        final AtomicReference<Consumer<String>> incoming=new AtomicReference<>();final AtomicInteger searches=new AtomicInteger();
        final AtomicBoolean ownerValid=new AtomicBoolean(true);final RealtimeBridge bridge;
        Fixture(boolean enabled){this(enabled,null,null);}
        Fixture(boolean enabled,RealtimeWebSearch.Searcher override,Executor executor){
            RealtimeBridge.Operation operation=new RealtimeBridge.Operation(){public void start(JsonNode input){}public AutoCloseable enter(){return ()->{};}public void verify(){if(!ownerValid.get())throw new IllegalStateException();}public void end(boolean uncertain){}public String id(){return null;}public String durability(){return null;}};
            bridge=new RealtimeBridge(new RealtimeBridge.Downstream(){public CompletionStage<Void> send(String text){down.add(text);return CompletableFuture.completedFuture(null);}public void close(){}},
                (model,key,receive,closed,failed)->{incoming.set(receive);return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String text){up.add(text);return CompletableFuture.completedFuture(null);}public void close(){}});},operation,
                (key,query,zone)->{assertEquals("test-key",key);assertEquals("latest fact",query);searches.incrementAndGet();if(override!=null)return override.search(key,query,zone);ObjectNode result=ModelGateway.JSON.createObjectNode().put("answer","verified answer");result.putArray("citations").addObject().put("url","https://example.org/source").put("title","source");return result;},executor==null?tasks::add:executor);
            bridge.receive("{\"type\":\"starhub.connect\",\"provider\":\"openai\",\"model\":\"gpt-realtime-2.1\",\"apiKey\":\"test-key\",\"webSearch\":"+enabled+"}");
            incoming.get().accept("{\"type\":\"session.updated\"}");
        }
        void started(){incoming.get().accept("{\"type\":\"response.created\",\"response\":{\"id\":\"response_1\"}}");}
        void done(String status){incoming.get().accept("{\"type\":\"response.done\",\"response\":{\"id\":\"response_1\",\"status\":\""+status+"\",\"output\":[{\"type\":\"function_call\",\"name\":\"search_web\",\"call_id\":\"call_1\",\"arguments\":\"{\\\"query\\\":\\\"latest fact\\\"}\"}]}}");}
        void call(String status){started();done(status);}
    }
    @Test void searchGuidancePreservesInstructionsAndIsNotEnabledWithoutOptIn()throws Exception{
        ObjectNode session=ModelGateway.JSON.createObjectNode().put("instructions","Keep this earlier instruction.");RealtimeBridge.enableSearch(session);
        String instructions=session.path("instructions").asText(),description=session.path("tools").path(0).path("description").asText();
        assertTrue(instructions.startsWith("Keep this earlier instruction.\n\n"));assertTrue(instructions.contains("creative writing"));assertTrue(instructions.contains("explicitly asks"));assertTrue(description.contains("fictional stories"));assertTrue(description.contains("untrusted"));
        Fixture disabled=new Fixture(false);JsonNode config=ModelGateway.JSON.readTree(disabled.up.get(0));assertFalse(config.path("session").has("tools"));assertFalse(config.path("session").path("instructions").asText().contains("Use web search"));disabled.bridge.close(false);
    }
    @Test void optInSearchCompletesOnceAndReturnsSourcesWithoutLeakingKey()throws Exception{
        Fixture f=new Fixture(true);JsonNode config=ModelGateway.JSON.readTree(f.up.get(0));assertEquals("search_web",config.path("session").path("tools").path(0).path("name").asText());
        f.call("completed");assertEquals(1,f.tasks.size());assertTrue(f.down.stream().anyMatch(v->v.contains("searching")&&v.contains("call_1")));
        f.tasks.remove(0).run();assertEquals(1,f.searches.get());assertTrue(f.up.stream().anyMatch(v->v.contains("function_call_output")&&v.contains("verified answer")));assertTrue(f.up.stream().anyMatch(v->v.contains("response.create")));
        assertTrue(f.down.stream().anyMatch(v->v.contains("completed")&&v.contains("example.org/source")&&v.contains("call_1")));assertFalse(String.join("",f.down).contains("test-key"));
        f.call("completed");assertTrue(f.tasks.isEmpty());assertEquals(1,f.searches.get());f.bridge.close(false);
    }
    @Test void disabledIncompleteAndCancelledResponsesDoNotRunSearch(){
        Fixture disabled=new Fixture(false);disabled.call("completed");assertTrue(disabled.tasks.isEmpty());disabled.bridge.close(false);
        Fixture f=new Fixture(true);f.call("cancelled");f.call("incomplete");assertTrue(f.tasks.isEmpty());assertEquals(0,f.searches.get());f.bridge.close(false);
    }
    @Test void userSpeechCancelsQueuedSearchWithoutSendingAStaleAnswer(){
        Fixture f=new Fixture(true);f.call("completed");f.incoming.get().accept("{\"type\":\"input_audio_buffer.speech_started\",\"item_id\":\"item_1\"}");f.tasks.remove(0).run();
        assertEquals(0,f.searches.get());assertTrue(f.down.stream().anyMatch(v->v.contains("cancelled")&&v.contains("call_1")));assertTrue(f.up.stream().anyMatch(v->v.contains("function_call_output")&&v.contains("cancelled")));
        assertFalse(f.up.stream().anyMatch(v->v.contains("response.create")));f.bridge.close(false);
    }
    @Test void completedOldResponseAfterUserInterruptionCannotStartPaidSearch(){
        Fixture f=new Fixture(true);f.started();f.incoming.get().accept("{\"type\":\"input_audio_buffer.speech_started\",\"item_id\":\"item_1\"}");f.done("completed");
        assertTrue(f.tasks.isEmpty());assertEquals(0,f.searches.get());assertTrue(f.up.stream().anyMatch(v->v.contains("function_call_output")&&v.contains("cancelled")));f.bridge.close(false);
    }
    @Test void interruptionDuringSearchDoesNotSendAStaleResult()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Fixture f=new Fixture(true,(key,query,zone)->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return ModelGateway.JSON.createObjectNode().put("answer","late result");},null);
        f.call("completed");Thread worker=new Thread(f.tasks.remove(0));worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
        f.incoming.get().accept("{\"type\":\"input_audio_buffer.speech_started\",\"item_id\":\"item_1\"}");release.countDown();worker.join(5000);assertFalse(worker.isAlive());
        assertEquals(1,f.searches.get());assertFalse(f.up.stream().anyMatch(v->v.contains("late result")||v.contains("response.create")));assertTrue(f.down.stream().anyMatch(v->v.contains("cancelled")&&v.contains("call_1")));f.bridge.close(false);
    }
    @Test void executorSaturationReturnsFailureWithoutNetwork(){
        Fixture f=new Fixture(true,null,task->{throw new RejectedExecutionException();});f.call("completed");
        assertEquals(0,f.searches.get());assertTrue(f.down.stream().anyMatch(v->v.contains("failed")&&v.contains("call_1")));assertTrue(f.up.stream().anyMatch(v->v.contains("function_call_output")&&v.contains("busy")));f.bridge.close(false);
    }
    @Test void explicitSearchCancelIsStrictAndNeverForwarded(){
        Fixture f=new Fixture(true);f.call("completed");f.bridge.receive("{\"type\":\"starhub.cancel_search\"}");f.tasks.remove(0).run();
        assertEquals(0,f.searches.get());assertTrue(f.down.stream().anyMatch(v->v.contains("cancelled")&&v.contains("call_1")));assertFalse(f.up.stream().anyMatch(v->v.contains("starhub.cancel_search")));f.bridge.close(false);
        Fixture malformed=new Fixture(true);malformed.bridge.receive("{\"type\":\"starhub.cancel_search\",\"extra\":true}");assertTrue(malformed.down.stream().anyMatch(v->v.contains("starhub.error")));assertFalse(malformed.up.stream().anyMatch(v->v.contains("cancel_search")));
    }
    @Test void interruptionAfterSearchCompletesSuppressesLateContinuation(){
        Fixture f=new Fixture(true);f.call("completed");f.tasks.remove(0).run();
        assertTrue(f.up.stream().anyMatch(v->v.contains("response.create")&&v.contains("starhub_search_call_id")&&v.contains("call_1")));
        f.bridge.receive("{\"type\":\"starhub.cancel_search\"}");assertTrue(f.up.stream().anyMatch(v->v.contains("response.cancel")));
        int delivered=f.down.size();
        f.incoming.get().accept("{\"type\":\"response.created\",\"response\":{\"id\":\"late_1\",\"metadata\":{\"starhub_search_call_id\":\"call_1\"}}}");
        f.incoming.get().accept("{\"type\":\"response.output_audio.delta\",\"response_id\":\"late_1\",\"delta\":\"AAAA\"}");
        f.incoming.get().accept("{\"type\":\"response.done\",\"response\":{\"id\":\"late_1\",\"status\":\"cancelled\"}}");assertEquals(delivered,f.down.size());
        f.incoming.get().accept("{\"type\":\"response.created\",\"response\":{\"id\":\"new_1\"}}");assertTrue(f.down.get(f.down.size()-1).contains("new_1"));f.bridge.close(false);
    }
    @Test void sessionSearchIdsRemainBoundedAndLimitIsReported(){
        Fixture f=new Fixture(true);
        for(int i=0;i<13;i++){
            String response="response_"+i,call="call_"+i;
            f.incoming.get().accept("{\"type\":\"response.created\",\"response\":{\"id\":\""+response+"\"}}");
            f.incoming.get().accept("{\"type\":\"response.done\",\"response\":{\"id\":\""+response+"\",\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"search_web\",\"call_id\":\""+call+"\",\"arguments\":\"{\\\"query\\\":\\\"latest fact\\\"}\"}]}}");
            if(i<12){assertEquals(1,f.tasks.size());f.tasks.remove(0).run();}
        }
        assertEquals(12,f.searches.get());assertTrue(f.tasks.isEmpty());assertTrue(f.down.stream().anyMatch(v->v.contains("starhub.error")&&v.contains("上限")));
    }
    @Test void changedOwnerOrBusyAdmissionNeverDispatchesSearch(){
        Fixture owner=new Fixture(true);owner.call("completed");owner.ownerValid.set(false);owner.tasks.remove(0).run();assertEquals(0,owner.searches.get());owner.bridge.close(false);
        Fixture busy=new Fixture(true);busy.call("completed");try(ProviderAdmission.Lease lease=ProviderAdmission.tryEnter()){assertNotNull(lease);busy.tasks.remove(0).run();}
        assertEquals(0,busy.searches.get());assertTrue(busy.down.stream().anyMatch(v->v.contains("failed")&&v.contains("call_1")));busy.bridge.close(false);
    }
    @Test void failureBlocksNewCallIdsUntilNewUserSpeech(){
        Fixture f=new Fixture(true,(key,query,zone)->{throw new java.io.IOException("unknown provider outcome");},null);
        f.call("completed");f.tasks.remove(0).run();assertEquals(1,f.searches.get());
        f.incoming.get().accept("{\"type\":\"response.created\",\"response\":{\"id\":\"response_2\",\"metadata\":{\"starhub_search_call_id\":\"call_1\"}}}");
        f.incoming.get().accept("{\"type\":\"response.done\",\"response\":{\"id\":\"response_2\",\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"search_web\",\"call_id\":\"call_2\",\"arguments\":\"{\\\"query\\\":\\\"latest fact\\\"}\"}]}}");
        assertTrue(f.tasks.isEmpty());assertEquals(1,f.searches.get());assertTrue(f.up.stream().anyMatch(v->v.contains("already failed")));
        f.incoming.get().accept("{\"type\":\"input_audio_buffer.speech_started\"}");
        f.incoming.get().accept("{\"type\":\"response.created\",\"response\":{\"id\":\"response_3\"}}");
        f.incoming.get().accept("{\"type\":\"response.done\",\"response\":{\"id\":\"response_3\",\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"search_web\",\"call_id\":\"call_3\",\"arguments\":\"{\\\"query\\\":\\\"latest fact\\\"}\"}]}}");
        assertEquals(1,f.tasks.size());f.tasks.remove(0).run();assertEquals(2,f.searches.get());f.bridge.close(false);
    }
    @Test void continuationEventBindsTheActualResponseForSuccessAndFailure()throws Exception{
        for(boolean success:new boolean[]{true,false}){
            Fixture f=new Fixture(true,success?null:(key,query,zone)->{throw new java.io.IOException("failed");},null);f.call("completed");f.tasks.remove(0).run();
            f.incoming.get().accept("{\"type\":\"response.created\",\"response\":{\"id\":\"unrelated\",\"metadata\":{\"starhub_search_call_id\":\"other\"}}}");
            assertFalse(f.down.stream().anyMatch(v->v.contains("continuation")));
            f.incoming.get().accept("{\"type\":\"response.created\",\"response\":{\"id\":\"next_1\",\"metadata\":{\"starhub_search_call_id\":\"call_1\"}}}");
            var event=ModelGateway.JSON.readTree(f.down.get(f.down.size()-2));assertEquals("continuation",event.path("status").asText());assertEquals("call_1",event.path("callId").asText());assertEquals("next_1",event.path("responseId").asText());assertEquals("response.created",ModelGateway.JSON.readTree(f.down.get(f.down.size()-1)).path("type").asText());f.bridge.close(false);
        }
    }
    @Test void revokedOwnerAfterNetworkPreventsApplyingSearchResults(){
        Fixture[] ref=new Fixture[1];Fixture f=new Fixture(true,(key,query,zone)->{ref[0].ownerValid.set(false);return ModelGateway.JSON.createObjectNode().put("answer","secret result");},null);ref[0]=f;f.call("completed");f.tasks.remove(0).run();
        assertEquals(1,f.searches.get());assertFalse(f.up.stream().anyMatch(v->v.contains("secret result")));assertTrue(f.down.stream().anyMatch(v->v.contains("starhub.error")));f.bridge.close(false);
    }
    @Test void searchUsesOfficialWebToolAndRejectsUnverifiedAnswers()throws Exception{
        AtomicReference<JsonNode> sent=new AtomicReference<>();
        ModelGateway gateway=new ModelGateway((uri,headers,body)->{
            assertEquals("https://api.openai.com/v1/responses",uri.toString());sent.set(ModelGateway.JSON.readTree(body));
            String response="{\"output\":[{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"sources\":[{\"url\":\"https://example.org/report\",\"title\":\"Report\"},{\"url\":\"http://example.org/insecure\"}]}},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"A fact.\"}]}]}";
            return new ModelGateway.Reply(200,response.getBytes(StandardCharsets.UTF_8));
        });
        ObjectNode result=RealtimeWebSearch.search(gateway,"test-key","latest fact","Europe/London");
        assertEquals("web_search",sent.get().path("tools").path(0).path("type").asText());assertEquals("gpt-6.2-sol",sent.get().path("model").asText());assertFalse(sent.get().path("store").asBoolean(true));
        assertEquals("required",sent.get().path("tool_choice").asText());assertEquals("web_search_call.action.sources",sent.get().path("include").path(0).asText());
        assertEquals("A fact.",result.path("answer").asText());assertEquals(1,result.path("citations").size());assertEquals("https://example.org/report",result.path("citations").path(0).path("url").asText());
        ModelGateway noSearch=new ModelGateway((uri,headers,body)->new ModelGateway.Reply(200,"{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"unverified\"}]}]}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(RealtimeWebSearch.FailureCode.NO_SEARCH,assertThrows(RealtimeWebSearch.Failure.class,()->RealtimeWebSearch.search(noSearch,"test-key","latest fact","UTC")).code);
    }
    @Test void sourcesMustBePublicHttpsAndSourceAbsenceIsDistinct()throws Exception{
        String value="{\"output\":[{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"sources\":[{\"url\":\"https://127.0.0.1/x\"},{\"url\":\"https://service.internal/x\"},{\"url\":\"https://localhost/x\"},{\"url\":\"https://example.org:444/x\"},{\"url\":\"oai-weather:feed\"}]}},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Weather\"}]}]}";
        ModelGateway gateway=new ModelGateway((uri,headers,body)->new ModelGateway.Reply(200,value.getBytes(StandardCharsets.UTF_8)));
        assertEquals(RealtimeWebSearch.FailureCode.NO_SOURCE,assertThrows(RealtimeWebSearch.Failure.class,()->RealtimeWebSearch.search(gateway,"test-key","weather","UTC")).code);
    }
    @Test void ordinaryChatRemainsOptionalAndCannotEnableInternalSearchPolicy()throws Exception{
        ObjectNode req=ModelGateway.JSON.createObjectNode().put("provider","openai").put("model","gpt-6.2-sol").put("apiKey","test-key").put("webSearch",true).put("forceRealtimeSearch",true);
        req.putArray("messages").addObject().put("role","user").put("content","hello");
        JsonNode body=ModelGateway.prepare(req,false).body;assertFalse(body.has("tool_choice"));assertFalse(body.has("include"));
    }
    @Test void weatherFeedRequiresCompletedOfficialToolSourceAndNeverFabricatesUrl()throws Exception{
        String suffix="},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Weather\"}]}]}";
        String prefix="{\"output\":[{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"sources\":[";
        java.util.function.Function<String,ModelGateway> gateway=source->new ModelGateway((uri,headers,body)->new ModelGateway.Reply(200,(prefix+source+"]}"+suffix).getBytes(StandardCharsets.UTF_8)));
        ObjectNode result=RealtimeWebSearch.search(gateway.apply("{\"type\":\"api\",\"name\":\"oai-weather\"}"),"test-key","weather","UTC");
        assertEquals("oai-weather",result.path("realtimeSource").asText());assertEquals(0,result.path("citations").size());assertTrue(result.path("sourceNotice").asText().contains("Do not invent"));
        for(String fake:List.of("{\"type\":\"url\",\"name\":\"oai-weather\"}","{\"type\":\"api\",\"name\":\"oai-weather-fake\"}","{\"name\":\"oai-weather\"}"))
            assertEquals(RealtimeWebSearch.FailureCode.NO_SOURCE,assertThrows(RealtimeWebSearch.Failure.class,()->RealtimeWebSearch.search(gateway.apply(fake),"test-key","weather","UTC")).code);
        String incomplete=(prefix+"{\"type\":\"api\",\"name\":\"oai-weather\"}] }"+suffix).replace("\"status\":\"completed\"","\"status\":\"failed\"");
        ModelGateway failed=new ModelGateway((uri,headers,body)->new ModelGateway.Reply(200,incomplete.getBytes(StandardCharsets.UTF_8)));
        assertEquals(RealtimeWebSearch.FailureCode.NO_SEARCH,assertThrows(RealtimeWebSearch.Failure.class,()->RealtimeWebSearch.search(failed,"test-key","weather","UTC")).code);
        String mixed="{\"output\":[{\"type\":\"web_search_call\",\"status\":\"failed\",\"action\":{\"type\":\"search\",\"sources\":[{\"url\":\"https://example.org/untrusted\"}]}},{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"sources\":[]}},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Weather\"}]}]}";
        ModelGateway mixedGateway=new ModelGateway((uri,headers,body)->new ModelGateway.Reply(200,mixed.getBytes(StandardCharsets.UTF_8)));
        assertEquals(RealtimeWebSearch.FailureCode.NO_SOURCE,assertThrows(RealtimeWebSearch.Failure.class,()->RealtimeWebSearch.search(mixedGateway,"test-key","weather","UTC")).code);
    }
    @Test void onlyWhitelistedSourceIsForwardedToMatchingCompletedEvent()throws Exception{
        for(String source:List.of("oai-weather","oai-finance")){
            Fixture f=new Fixture(true,(key,query,zone)->ModelGateway.JSON.createObjectNode().put("answer","weather").put("realtimeSource",source),null);
            f.call("completed");f.tasks.remove(0).run();JsonNode event=ModelGateway.JSON.readTree(f.down.stream().filter(v->v.contains("starhub.web_search")&&v.contains("\"status\":\"completed\"")).findFirst().orElseThrow());
            assertEquals(source.equals("oai-weather")?"oai-weather":"",event.path("realtimeSource").asText());f.bridge.close(false);
        }
    }
    @Test void weatherSourceIsShownToUiButNeverSpokenAsInternalIdentifier()throws Exception{
        Fixture f=new Fixture(true,(key,query,zone)->{
            ObjectNode result=ModelGateway.JSON.createObjectNode().put("answer","From OAI-WEATHER: 12 C").put("realtimeSource","oai-weather").put("notice","Use Oai-Weather as evidence");
            result.putArray("citations").addObject().put("url","https://example.org/report").put("title","OAI-Weather report");
            result.withArray("citations").addObject().put("url","https://example.org/oai-weather").put("title","source");return result;
        },null);
        f.call("completed");f.tasks.remove(0).run();
        JsonNode ui=ModelGateway.JSON.readTree(f.down.stream().filter(v->v.contains("starhub.web_search")&&v.contains("\"status\":\"completed\"")).findFirst().orElseThrow());
        assertEquals("oai-weather",ui.path("realtimeSource").asText());assertEquals(2,ui.path("citations").size());
        JsonNode call=ModelGateway.JSON.readTree(f.up.stream().filter(v->v.contains("function_call_output")).findFirst().orElseThrow());String text=call.path("item").path("output").asText();
        assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("oai-weather"));JsonNode model=ModelGateway.JSON.readTree(text);
        assertTrue(model.path("answer").asText().contains("OpenAI real-time weather data"));assertTrue(model.path("sourceNotice").asText().contains("Do not invent"));
        assertEquals(1,model.path("citations").size());assertEquals("https://example.org/report",model.path("citations").path(0).path("url").asText());assertFalse(model.has("realtimeSource"));f.bridge.close(false);
    }
    @Test void searchFailuresLogOnlyFixedCategoriesAndNeverExceptionDetails(){
        List<String> logged=new ArrayList<>();java.util.logging.Logger log=java.util.logging.Logger.getLogger(RealtimeBridge.class.getName());
        java.util.logging.Handler handler=new java.util.logging.Handler(){public void publish(java.util.logging.LogRecord r){logged.add(r.getMessage());}public void flush(){}public void close(){}};
        log.addHandler(handler);
        try{Fixture f=new Fixture(true,(key,query,zone)->{throw new java.io.IOException("PRIVATE query and key");},null);f.call("completed");f.tasks.remove(0).run();assertEquals(1,f.searches.get());f.bridge.close(false);}
        finally{log.removeHandler(handler);}
        assertEquals(List.of("Realtime web search failure: UPSTREAM"),logged);assertFalse(logged.get(0).contains("PRIVATE"));
        assertEquals("NO_SOURCE",RealtimeBridge.searchFailureCategory(new RealtimeWebSearch.Failure(RealtimeWebSearch.FailureCode.NO_SOURCE)));
        assertEquals("NO_SEARCH",RealtimeBridge.searchFailureCategory(new RealtimeWebSearch.Failure(RealtimeWebSearch.FailureCode.NO_SEARCH)));
        assertEquals("TIMEOUT",RealtimeBridge.searchFailureCategory(new java.net.SocketTimeoutException()));
        assertEquals("BUSY",RealtimeBridge.searchFailureCategory(new RejectedExecutionException()));
    }
}
