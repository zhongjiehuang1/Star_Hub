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
        ObjectNode r=req("openai","gpt-6.1-sol","chat");params(r).put("temperature",.3);assertThrows(ModelGateway.Problem.class,()->gateway.chat(r));
        r.put("model","gpt-4.1-mini");params(r).put("temperature",3);assertThrows(ModelGateway.Problem.class,()->gateway.chat(r));
        params(r).put("max_tokens",12.5);assertThrows(ModelGateway.Problem.class,()->gateway.chat(r));
        params(r).putObject("json_schema").put("type","object");assertThrows(ModelGateway.Problem.class,()->gateway.chat(r));
        params(r).put("response_format","json_schema");assertThrows(ModelGateway.Problem.class,()->gateway.chat(r));assertEquals(0,calls.get());
    }
    @Test void zeroAndFalseArePreservedAndMissingSettingsStayMissing()throws Exception{
        ObjectNode r=req("openai","gpt-4o","chat");params(r).put("temperature",0).put("logprobs",false).put("seed",0);
        new ModelGateway((u,h,b)->{JsonNode x=ModelGateway.JSON.readTree(b);assertEquals(0,x.path("temperature").asDouble());assertTrue(x.path("logprobs").isBoolean());assertFalse(x.path("logprobs").asBoolean());assertEquals(0,x.path("seed").asInt());assertFalse(x.has("top_p"));assertFalse(x.has("provider"));return new ModelGateway.Reply(200,"{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes());}).chat(r);
    }
    @Test void responseSettingsMapToResponsesSchemaAndRetainEffort()throws Exception{
        ObjectNode r=req("openai","gpt-6.1-sol","chat").put("effort","high");ObjectNode p=params(r).put("max_tokens",8000).put("verbosity","low").put("response_format","json_schema");p.putObject("json_schema").put("type","object");
        new ModelGateway((u,h,b)->{JsonNode x=ModelGateway.JSON.readTree(b);assertTrue(u.toString().endsWith("/responses"));assertEquals(8000,x.path("max_output_tokens").asInt());assertEquals("high",x.path("reasoning").path("effort").asText());assertEquals("low",x.path("text").path("verbosity").asText());assertEquals("json_schema",x.path("text").path("format").path("type").asText());assertFalse(x.has("max_tokens"));assertFalse(x.path("store").asBoolean());return new ModelGateway.Reply(200,"{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}".getBytes());}).chat(r);
    }
    @Test void geminiMapsSamplingAndStructuredOutputWithoutOverwritingThinking()throws Exception{
        ObjectNode r=req("gemini","gemini-2.5-flash","chat");params(r).put("max_tokens",5000).put("thinking_budget",2000).put("top_p",.8).put("response_format","json_schema").putObject("json_schema").put("type","object");
        new ModelGateway((u,h,b)->{JsonNode x=ModelGateway.JSON.readTree(b).path("generationConfig");assertEquals(5000,x.path("maxOutputTokens").asInt());assertEquals(2000,x.path("thinkingConfig").path("thinkingBudget").asInt());assertEquals(.8,x.path("topP").asDouble());assertEquals("application/json",x.path("responseMimeType").asText());assertEquals("object",x.path("responseJsonSchema").path("type").asText());return new ModelGateway.Reply(200,"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}".getBytes());}).chat(r);
    }
    @Test void claudeRejectsInvalidThinkingModeAndChecksBudget()throws Exception{
        ObjectNode r=req("anthropic","claude-fable-5-1","chat");params(r).put("thinking","disabled");assertThrows(ModelGateway.Problem.class,()->Parameters.validate(r,model(r),"chat"));
        r.put("model","claude-haiku-5-5").put("effort","xhigh");assertThrows(ModelGateway.Problem.class,()->Parameters.validate(r,model(r),"chat"));
        r.put("model","claude-sonnet-4-5").put("effort","default");params(r).put("thinking","enabled").put("thinking_budget",4096);assertThrows(ModelGateway.Problem.class,()->Parameters.validate(r,model(r),"chat"));
        ((ObjectNode)r.path("parameters")).put("max_tokens",6000);new ModelGateway((u,h,b)->{JsonNode x=ModelGateway.JSON.readTree(b);assertEquals(4096,x.path("thinking").path("budget_tokens").asInt());assertEquals(6000,x.path("max_tokens").asInt());return new ModelGateway.Reply(200,"{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}".getBytes());}).chat(r);
    }
    @Test void catalogHasUniqueTypedSettingsAndKnownDependencyFields(){
        for(JsonNode m:Catalog.DATA.path("models"))for(JsonNode settings:m.path("settings")){
            Set<String> keys=new HashSet<>();for(JsonNode f:settings){assertTrue(keys.add(f.path("key").asText()),m.path("uid").asText());assertFalse(f.path("label").asText().isBlank());}
            for(JsonNode f:settings){f.path("when").fieldNames().forEachRemaining(k->assertTrue(k.equals("$effort")||keys.contains(k),m.path("uid")+" "+k));}
        }
    }
}
