package com.starhub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ParameterGatewayTest {
    ObjectNode req(String provider,String model,String task){ObjectNode r=ModelGateway.JSON.createObjectNode().put("provider",provider).put("model",model).put("task",task).put("apiKey","test-key").put("prompt","生成测试");r.putArray("messages").addObject().put("role","user").put("content","生成测试");return r;}
    String image="data:image/png;base64,aGk=";
    MediaGateway.Response media(String type,String text){return new MediaGateway.Response(200,type,text.getBytes(StandardCharsets.UTF_8));}
    ObjectNode params(ObjectNode r){return r.putObject("parameters");}
    JsonNode model(ObjectNode r){return Catalog.find(r.path("provider").asText(),r.path("model").asText());}
    @Test void unsupportedOutOfRangeAndConditionalSettingsNeverReachNetwork(){
        AtomicInteger calls=new AtomicInteger();ModelGateway gateway=new ModelGateway((u,h,b)->{calls.incrementAndGet();return null;});
        ObjectNode r=req("openai","gpt-6.2-sol","chat");params(r).put("temperature",.3);assertThrows(ModelGateway.Problem.class,()->gateway.chat(r));
        r.put("model","gpt-6.2-sol");params(r).put("max_tokens",129000);assertThrows(ModelGateway.Problem.class,()->gateway.chat(r));
        params(r).put("max_tokens",12.5);assertThrows(ModelGateway.Problem.class,()->gateway.chat(r));
        params(r).putObject("json_schema").put("type","object");assertThrows(ModelGateway.Problem.class,()->gateway.chat(r));
        params(r).put("response_format","json_schema");assertThrows(ModelGateway.Problem.class,()->gateway.chat(r));assertEquals(0,calls.get());
    }
    @Test void unsupportedLegacyParametersAreRejectedBeforeDispatch(){
        AtomicInteger calls=new AtomicInteger();ObjectNode r=req("openai","gpt-6.2-sol","chat");params(r).put("temperature",0).put("logprobs",false).put("seed",0);
        assertThrows(ModelGateway.Problem.class,()->new ModelGateway((u,h,b)->{calls.incrementAndGet();return null;}).chat(r));assertEquals(0,calls.get());
    }
    @Test void responseSettingsMapToResponsesSchemaAndRetainEffort()throws Exception{
        ObjectNode r=req("openai","gpt-6.2-sol","chat").put("effort","high");ObjectNode p=params(r).put("max_tokens",8000).put("verbosity","low").put("response_format","json_schema");p.putObject("json_schema").put("type","object");
        new ModelGateway((u,h,b)->{JsonNode x=ModelGateway.JSON.readTree(b);assertTrue(u.toString().endsWith("/responses"));assertEquals(8000,x.path("max_output_tokens").asInt());assertEquals("high",x.path("reasoning").path("effort").asText());assertEquals("low",x.path("text").path("verbosity").asText());assertEquals("json_schema",x.path("text").path("format").path("type").asText());assertFalse(x.has("max_tokens"));assertFalse(x.path("store").asBoolean());return new ModelGateway.Reply(200,"{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}".getBytes());}).chat(r);
    }
    @Test void geminiCurrentModelMapsOutputLimitAndReasoning()throws Exception{
        ObjectNode r=req("gemini","gemini-4-carbon","chat").put("effort","high");params(r).put("max_tokens",5000);
        new ModelGateway((u,h,b)->{JsonNode x=ModelGateway.JSON.readTree(b).path("generationConfig");assertEquals(5000,x.path("maxOutputTokens").asInt());assertEquals("high",x.path("thinkingConfig").path("thinkingLevel").asText());return new ModelGateway.Reply(200,"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}".getBytes());}).chat(r);
    }
    @Test void claudeCurrentModelRejectsUnsupportedThinkingParameters(){
        ObjectNode r=req("anthropic","claude-fable-5-2","chat");params(r).put("thinking","disabled");assertThrows(ModelGateway.Problem.class,()->Parameters.validate(r,model(r),"chat"));
    }
    @Test void catalogHasUniqueTypedSettingsAndKnownDependencyFields(){
        for(JsonNode m:Catalog.DATA.path("models"))for(JsonNode settings:m.path("settings")){
            Set<String> keys=new HashSet<>();for(JsonNode f:settings){assertTrue(keys.add(f.path("key").asText()),m.path("uid").asText());assertFalse(f.path("label").asText().isBlank());}
            for(JsonNode f:settings){f.path("when").fieldNames().forEachRemaining(k->assertTrue(k.equals("$effort")||keys.contains(k),m.path("uid")+" "+k));}
        }
    }
}
