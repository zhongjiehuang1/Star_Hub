package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogGatewayTest {
    ObjectNode request(String provider,String model,String task){
        ObjectNode r=ModelGateway.JSON.createObjectNode().put("provider",provider).put("model",model).put("task",task).put("apiKey","test-key").put("prompt","测试内容");
        r.putArray("messages").addObject().put("role","user").put("content","你好");return r;
    }
    JsonNode first(String provider,String task){for(JsonNode m:Catalog.DATA.path("models"))if(m.path("provider").asText().equals(provider)&&Catalog.contains(m.path("categories"),task))return m;throw new AssertionError();}
    MediaGateway.Response response(String text){return new MediaGateway.Response(200,"application/json",text.getBytes(StandardCharsets.UTF_8));}
    @Test void nativeOpenAiNewModelsUseResponses()throws Exception{
        ModelGateway g=new ModelGateway((uri,headers,bytes)->{assertTrue(uri.toString().endsWith("/responses"));JsonNode b=ModelGateway.JSON.readTree(bytes);assertTrue(b.has("input"));assertEquals("high",b.path("reasoning").path("effort").asText());assertFalse(b.has("reasoning_effort"));return new ModelGateway.Reply(200,"{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}".getBytes(StandardCharsets.UTF_8));});
        assertEquals("ok",g.chat(request("openai","gpt-6.2-sol","chat").put("effort","high")).path("text").asText());
    }
    @Test void internalCatalogRetainsRecentModelsForEveryProviderAndBuildsRequests() throws Exception {
        JsonNode models=Catalog.DATA.path("models");
        assertTrue(models.size()>=17);Set<String> providers=new HashSet<>();Set<String> ids=new HashSet<>();
        for(JsonNode model:models){
            String provider=model.path("provider").asText(),id=model.path("id").asText();
            providers.add(provider);assertTrue(ids.add(provider+"|"+id));
            if(!model.path("batch").asBoolean() && Catalog.contains(model.path("categories"),"chat")){
                ModelGateway.Prepared prepared=ModelGateway.prepare(request(provider,id,"chat"),true);
                assertEquals(id,prepared.model);
            }
        }
        assertEquals(Set.of("openai","anthropic","gemini","deepseek","qwen","moonshot"),providers);
        assertFalse(ids.stream().anyMatch(id->id.startsWith("xai|")));assertTrue(ids.contains("qwen|qwen4-max"));
        assertTrue(ids.contains("openai|gpt-6.2-sol"));
        assertThrows(ModelGateway.Problem.class,()->Catalog.find("openai","gpt-4.1-mini"));
    }

    @Test void publicCatalogExposesOnlyOpenAiAndKeepsItsTaskCoverage() {
        JsonNode catalog=Catalog.publicData();
        assertEquals(1,catalog.path("providers").size());
        assertEquals("openai",catalog.path("providers").get(0).path("id").asText());
        assertEquals(1,Catalog.publicProviders().size());
        assertEquals("openai",Catalog.publicProviders().get(0).path("id").asText());
        assertEquals(1,catalog.path("sources").size());
        assertEquals("OpenAI",catalog.path("sources").get(0).path("name").asText());
        Set<String> available=new HashSet<>();
        for(JsonNode model:catalog.path("models")){
            assertEquals("openai",model.path("provider").asText());
            available.add(model.path("id").asText());
        }
        assertTrue(available.containsAll(Set.of("gpt-6.2-sol","gpt-image-2.6-flare","gpt-live-1","gpt-6.2-sol:batch")));
        for(String choice:List.of("primary","secondary","preferredDefault")){
            JsonNode featured=catalog.path("featured").path(choice);
            assertEquals(1,featured.size());
            assertTrue(featured.has("openai"));
        }
        assertEquals(6,Catalog.DATA.path("providers").size());
    }

    @Test void modelEntryPointsRejectClosedProvidersBeforeSessionOrDispatch() throws Exception {
        ApiServlet api=new ApiServlet();
        Map<String,String> blockedRequests=Map.of("/chat","anthropic","/run","deepseek","/batch","qwen","/check","gemini","/memory-extract","moonshot");
        for(var entry:blockedRequests.entrySet()){
            HttpServletRequest req=mock(HttpServletRequest.class);
            when(req.getPathInfo()).thenReturn(entry.getKey());
            when(req.getContentType()).thenReturn("application/json");
            when(req.getHeader("Origin")).thenReturn(DeploymentConfig.allowedOrigins().iterator().next());
            when(req.getHeader("X-Starhub-Client")).thenReturn("web");
            ObjectNode payload=ModelGateway.JSON.createObjectNode().put("provider",entry.getValue()).put("operationId",UUID.randomUUID().toString());
            ByteArrayInputStream bytes=new ByteArrayInputStream(ModelGateway.JSON.writeValueAsBytes(payload));
            when(req.getInputStream()).thenReturn(new ServletInputStream(){
                public int read(){return bytes.read();}
                public boolean isFinished(){return bytes.available()==0;}
                public boolean isReady(){return true;}
                public void setReadListener(ReadListener listener){}
            });
            var result=new AtomicTransactionsTest.Capture();
            api.doPost(req,result.response);
            assertEquals(403,result.status.get(),entry.getKey());
            assertEquals("PROVIDER_DISABLED",ModelGateway.JSON.readTree(result.bytes.toByteArray()).path("code").asText());
            verify(req,never()).getSession();
            verify(req,never()).getSession(anyBoolean());
        }
    }
}
