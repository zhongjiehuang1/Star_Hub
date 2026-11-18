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

class AdvancedGatewayTest {
    MediaGateway.Response response(String body){return new MediaGateway.Response(200,"application/json",body.getBytes(StandardCharsets.UTF_8));}
    @Test void officialRealtimeModelsHaveCompatibleConfig(){
        int count=0;for(JsonNode m:Catalog.DATA.path("models"))if(Catalog.contains(m.path("categories"),"realtime")){
            count++;JsonNode e=RealtimeBridge.sessionConfig(m,"");JsonNode input=e.path("session").path("audio").path("input");assertEquals(24000,input.path("format").path("rate").asInt());assertTrue(input.path("noise_reduction").isNull());
            boolean transcription=!Catalog.contains(m.path("outputs"),"audio");assertEquals(transcription?"transcription":"realtime",e.path("session").path("type").asText());assertEquals(transcription,input.has("transcription"));if(!transcription){assertEquals("semantic_vad",input.path("turn_detection").path("type").asText());assertEquals("medium",input.path("turn_detection").path("eagerness").asText());assertTrue(input.path("turn_detection").path("create_response").asBoolean());assertTrue(input.path("turn_detection").path("interrupt_response").asBoolean());}
        }assertTrue(count>=2);assertNotNull(Catalog.find("openai","gpt-realtime-2.1"));assertNotNull(Catalog.find("openai","gpt-realtime-2.1-mini"));
    }
    @Test void accentKeepsUserInstructionsAndRejectsUnknownValues(){
        ObjectNode session=ModelGateway.JSON.createObjectNode().put("instructions","Be concise.");
        RealtimeBridge.applyAccent(session,"british",false);assertTrue(session.path("instructions").asText().contains("Be concise."));assertTrue(session.path("instructions").asText().contains("British English"));
        assertThrows(IllegalArgumentException.class,()->RealtimeBridge.applyAccent(session,"untrusted",false));
        assertThrows(IllegalArgumentException.class,()->RealtimeBridge.applyAccent(session,"american",true));
    }
    @Test void realtimeCanEnableTranscriptionFallbackWithoutRestartingSession() throws Exception {
        List<String> up=new ArrayList<>(),down=new ArrayList<>();AtomicReference<Consumer<String>> incoming=new AtomicReference<>();
        RealtimeBridge bridge=new RealtimeBridge(new RealtimeBridge.Downstream(){public CompletionStage<Void> send(String s){down.add(s);return CompletableFuture.completedFuture(null);}public void close(){}},(model,key,receive,closed,failed)->{incoming.set(receive);return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String s){up.add(s);return CompletableFuture.completedFuture(null);}public void close(){}});});
        bridge.receive("{\"type\":\"starhub.connect\",\"provider\":\"openai\",\"model\":\"gpt-realtime-2.1\",\"apiKey\":\"test-key\",\"accent\":\"american\",\"parameters\":{\"instructions\":\"Be concise.\"}}");
        assertTrue(ModelGateway.JSON.readTree(up.get(0)).path("session").path("instructions").asText().contains("American English"));
        incoming.get().accept("{\"type\":\"session.updated\"}");bridge.receive("{\"type\":\"starhub.enable_input_transcription\"}");
        assertEquals("gpt-transcribe",ModelGateway.JSON.readTree(up.get(1)).path("session").path("audio").path("input").path("transcription").path("model").asText());
        incoming.get().accept("{\"type\":\"session.updated\"}");assertEquals(1,down.stream().filter(s->s.contains("starhub.ready")).count());bridge.close(false);
    }
    @Test void conversationTranscriptionUsesTheSameInputAndRelaysOnlySafeFailureCodes() throws Exception {
        List<String> up=new ArrayList<>(),down=new ArrayList<>();AtomicReference<Consumer<String>> incoming=new AtomicReference<>();
        RealtimeBridge bridge=new RealtimeBridge(new RealtimeBridge.Downstream(){public CompletionStage<Void> send(String s){down.add(s);return CompletableFuture.completedFuture(null);}public void close(){}},(model,key,receive,closed,failed)->{incoming.set(receive);return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String s){up.add(s);return CompletableFuture.completedFuture(null);}public void close(){}});});
        bridge.receive("{\"type\":\"starhub.connect\",\"provider\":\"openai\",\"model\":\"gpt-realtime-2.1-mini\",\"apiKey\":\"test-key\",\"transcriptMode\":\"conversation\",\"parameters\":{}}");
        JsonNode input=ModelGateway.JSON.readTree(up.get(0)).path("session").path("audio").path("input");assertEquals("gpt-transcribe",input.path("transcription").path("model").asText());assertEquals("semantic_vad",input.path("turn_detection").path("type").asText());
        incoming.get().accept("{\"type\":\"session.updated\"}");incoming.get().accept("{\"type\":\"conversation.item.input_audio_transcription.failed\",\"item_id\":\"u1\",\"error\":{\"code\":\"audio_unintelligible\",\"message\":\"private provider detail\"}}");
        JsonNode failure=ModelGateway.JSON.readTree(down.get(down.size()-1));assertEquals("u1",failure.path("item_id").asText());assertEquals("audio_unintelligible",failure.path("error_code").asText());assertFalse(failure.toString().contains("private provider detail"));bridge.close(false);
        ObjectNode session=(ObjectNode)RealtimeBridge.sessionConfig(Catalog.find("openai","gpt-realtime-2.1-mini"),"").path("session");ObjectNode request=ModelGateway.JSON.createObjectNode().put("transcriptMode","bad");assertThrows(IllegalArgumentException.class,()->RealtimeBridge.applyTranscriptionMode(session,request,false));
    }
    @Test void liveTranscriptUsesDedicatedTranscriptionSessionWithoutServerVad() {
        ObjectNode live=ModelGateway.JSON.createObjectNode().put("id","gpt-live-transcribe");live.putArray("outputs").add("text");live.putArray("voices");
        JsonNode input=RealtimeBridge.sessionConfig(live,"").path("session").path("audio").path("input");
        assertTrue(input.path("turn_detection").isNull());assertEquals("gpt-live-transcribe",input.path("transcription").path("model").asText());
        assertEquals("wss://api.openai.com/v1/realtime?intent=transcription",ApiRoutes.realtime("gpt-live-transcribe"));
    }
    @Test void realtimeTranscriptionLanguageUsesValidatedModelSpecificHints(){
        ObjectNode session=(ObjectNode)RealtimeBridge.sessionConfig(Catalog.find("openai","gpt-realtime-2.1"),"").path("session");
        ObjectNode request=ModelGateway.JSON.createObjectNode().put("transcriptMode","conversation").put("transcriptionLanguage","zh");
        RealtimeBridge.applyTranscriptionMode(session,request,false);
        JsonNode config=session.path("audio").path("input").path("transcription");assertEquals("gpt-transcribe",config.path("model").asText());assertEquals("zh",config.path("languages").path(0).asText());assertFalse(config.has("language"));
        ObjectNode bad=ModelGateway.JSON.createObjectNode().put("transcriptMode","conversation").put("transcriptionLanguage","zh-XX");assertThrows(IllegalArgumentException.class,()->RealtimeBridge.applyTranscriptionMode(session,bad,false));
        ObjectNode live=ModelGateway.JSON.createObjectNode().put("id","gpt-live-transcribe");live.putArray("outputs").add("text");live.putArray("voices");
        ObjectNode liveSession=(ObjectNode)RealtimeBridge.sessionConfig(live,"").path("session");RealtimeBridge.applyTranscriptionMode(liveSession,ModelGateway.JSON.createObjectNode().put("transcriptionLanguage","en"),true);
        assertEquals("en",liveSession.path("audio").path("input").path("transcription").path("languages").path(0).asText());
    }
    @Test void liveTranscriptRelaysIncrementalTextWithoutManualCommit() throws Exception {
        List<String> up=new ArrayList<>(),down=new ArrayList<>();AtomicReference<Consumer<String>> incoming=new AtomicReference<>();
        RealtimeBridge bridge=new RealtimeBridge(new RealtimeBridge.Downstream(){public CompletionStage<Void> send(String s){down.add(s);return CompletableFuture.completedFuture(null);}public void close(){}},(model,key,receive,closed,failed)->{
            assertEquals("gpt-live-transcribe",model);incoming.set(receive);return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String s){up.add(s);return CompletableFuture.completedFuture(null);}public void close(){}});
        });
        bridge.receive("{\"type\":\"starhub.connect\",\"provider\":\"openai\",\"model\":\"gpt-live-transcribe\",\"apiKey\":\"test-key\",\"parameters\":{}}");
        assertEquals("gpt-live-transcribe",ModelGateway.JSON.readTree(up.get(0)).path("session").path("audio").path("input").path("transcription").path("model").asText());
        incoming.get().accept("{\"type\":\"session.updated\"}");bridge.receive("{\"type\":\"input_audio_buffer.append\",\"audio\":\"AAA=\"}");
        incoming.get().accept("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"i1\",\"delta\":\"hello\"}");
        assertTrue(down.stream().anyMatch(v->v.contains("hello")));assertEquals(2,up.size());bridge.close(false);
    }
    @Test void realtimeHandshakeRelaysAudioAndRedactsErrors()throws Exception{
        List<String> up=new ArrayList<>(),down=new ArrayList<>();AtomicReference<Consumer<String>> incoming=new AtomicReference<>();AtomicBoolean upClosed=new AtomicBoolean(),downClosed=new AtomicBoolean();
        RealtimeBridge bridge=new RealtimeBridge(new RealtimeBridge.Downstream(){public CompletionStage<Void> send(String s){down.add(s);return CompletableFuture.completedFuture(null);}public void close(){downClosed.set(true);}},(model,key,receive,closed,failed)->{
            assertEquals("gpt-realtime-2.1",model);assertEquals("test-key",key);incoming.set(receive);return CompletableFuture.completedFuture(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String s){up.add(s);return CompletableFuture.completedFuture(null);}public void close(){upClosed.set(true);}});
        });
        bridge.receive("{\"type\":\"starhub.connect\",\"provider\":\"openai\",\"model\":\"gpt-realtime-2.1\",\"apiKey\":\"test-key\"}");assertTrue(up.get(0).contains("session.update"));assertFalse(up.get(0).contains("test-key"));
        incoming.get().accept("{\"type\":\"session.updated\"}");bridge.receive("{\"type\":\"input_audio_buffer.append\",\"audio\":\"AAA=\"}");bridge.receive("{\"type\":\"input_audio_buffer.commit\"}");assertEquals(3,up.size());
        incoming.get().accept("{\"type\":\"error\",\"error\":{\"code\":\"invalid_event\",\"message\":\"test-key private material\"}}");assertFalse(String.join("",down).contains("test-key"));
        bridge.close();assertTrue(upClosed.get());assertTrue(downClosed.get());
    }
    @Test void realtimeRejectsWrongOriginKeysAndUnsafeClientEvents(){
        assertFalse(new RealtimeEndpoint.Origin().checkOrigin("https://evil.example"));assertTrue(new RealtimeEndpoint.Origin().checkOrigin("https://starhub.zhongjiehuang.com"));
        ObjectNode event=ModelGateway.JSON.createObjectNode().put("type","input_audio_buffer.append").put("audio","!");assertThrows(IllegalArgumentException.class,()->RealtimeBridge.validateEvent(event,false));
        event.put("type","response.create");assertThrows(IllegalArgumentException.class,()->RealtimeBridge.validateEvent(event,true));
        event.putObject("response").put("model","other-model");assertThrows(IllegalArgumentException.class,()->RealtimeBridge.validateEvent(event,false));
    }
    @Test void disconnectWhileConnectingClosesLateSocket(){
        CompletableFuture<RealtimeBridge.Socket> pending=new CompletableFuture<>();AtomicBoolean closed=new AtomicBoolean();
        RealtimeBridge bridge=new RealtimeBridge(new RealtimeBridge.Downstream(){public CompletionStage<Void> send(String s){return CompletableFuture.completedFuture(null);}public void close(){}},(a,b,c,d,e)->pending);
        bridge.receive("{\"type\":\"starhub.connect\",\"provider\":\"openai\",\"model\":\"gpt-realtime-2.1\",\"apiKey\":\"test-key\"}");bridge.close();
        pending.complete(new RealtimeBridge.Socket(){public CompletionStage<Void> send(String s){throw new AssertionError();}public void close(){closed.set(true);}});assertTrue(closed.get());
    }
    @Test void imageMessagesMapToAllThreeProtocolsAndResponses()throws Exception{
        String image="data:image/png;base64,AAAA";
        String[][] cases={{"openai","gpt-6.1-sol"},{"anthropic","claude-fable-5-2"},{"gemini","gemini-4-carbon"}};
        for(String[] item:cases){
            ModelGateway gateway=new ModelGateway((uri,headers,bytes)->{
                JsonNode body=ModelGateway.JSON.readTree(bytes);String result;
                if(item[0].equals("gemini")){assertEquals("AAAA",body.path("contents").path(0).path("parts").path(1).path("inlineData").path("data").asText());result="{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}";}
                else if(item[0].equals("anthropic")){assertEquals("base64",body.path("messages").path(0).path("content").path(1).path("source").path("type").asText());result="{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}";}
                else if(item[0].equals("openai")){assertEquals("input_image",body.path("input").path(1).path("content").path(1).path("type").asText());assertEquals(image,body.path("input").path(1).path("content").path(1).path("image_url").asText());result="{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}";}
                else {assertEquals(image,body.path("messages").path(0).path("content").path(1).path("image_url").path("url").asText());result="{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";}
                return new ModelGateway.Reply(200,result.getBytes(StandardCharsets.UTF_8));
            });
            ObjectNode r=ModelGateway.JSON.createObjectNode().put("provider",item[0]).put("model",item[1]).put("apiKey","test-key");r.putArray("messages").addObject().put("role","user").put("content","描述图片").put("image",image);assertEquals("ok",gateway.chat(r).path("text").asText());
        }
    }

}
