package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogGatewayTest {
    ObjectNode request(String provider,String model,String task){
        ObjectNode r=ModelGateway.JSON.createObjectNode().put("provider",provider).put("model",model).put("task",task).put("apiKey","test-key").put("prompt","测试内容");
        r.putArray("messages").addObject().put("role","user").put("content","你好");return r;
    }
    JsonNode first(String provider,String task){for(JsonNode m:Catalog.DATA.path("models"))if(m.path("provider").asText().equals(provider)&&Catalog.contains(m.path("categories"),task))return m;throw new AssertionError();}
    MediaGateway.Response response(String text){return new MediaGateway.Response(200,"application/json",text.getBytes(StandardCharsets.UTF_8));}
    @Test void nativeOpenAiNewModelsUseResponses()throws Exception{
        ModelGateway g=new ModelGateway((uri,headers,bytes)->{assertTrue(uri.toString().endsWith("/responses"));JsonNode b=ModelGateway.JSON.readTree(bytes);assertTrue(b.has("input"));assertEquals("high",b.path("reasoning").path("effort").asText());assertFalse(b.has("reasoning_effort"));return new ModelGateway.Reply(200,"{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}".getBytes(StandardCharsets.UTF_8));});
        assertEquals("ok",g.chat(request("openai","gpt-6.1-sol","chat").put("effort","high")).path("text").asText());
    }
    @Test void curatedPickerHasRecentModelsForEveryProviderAndBuildsRequests() throws Exception {
        JsonNode models=Catalog.publicData().path("models");
        assertEquals(22,models.size());Set<String> providers=new HashSet<>();Set<String> ids=new HashSet<>();
        for(JsonNode model:models){
            String provider=model.path("provider").asText(),id=model.path("id").asText();
            providers.add(provider);assertTrue(ids.add(provider+"|"+id));
            if(Catalog.contains(model.path("categories"),"chat")){
                ModelGateway.Prepared prepared=ModelGateway.prepare(request(provider,id,"chat"),true);
                assertEquals(id,prepared.model);
            }
        }
        assertEquals(Set.of("openai","anthropic","gemini","deepseek","qwen","moonshot","xai"),providers);
        assertTrue(ids.contains("xai|grok-4.7"));assertTrue(ids.contains("qwen|qwen3.8-max"));
        assertFalse(ids.contains("openai|gpt-4.1-mini"));
        assertNotNull(Catalog.find("openai","gpt-4.1-mini"));
    }
}
