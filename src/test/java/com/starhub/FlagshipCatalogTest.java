package com.starhub;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FlagshipCatalogTest {
 ObjectNode chat(JsonNode m){ObjectNode r=ModelGateway.JSON.createObjectNode().put("provider",m.path("provider").asText()).put("model",m.path("id").asText()).put("apiKey","offline-test-key");r.putArray("messages").addObject().put("role","user").put("content","Offline schema test").put("image","data:image/png;base64,iVBORw0KGgo=");return r;}
 @Test void allSixGeneralPicksPrepareTextImageWithoutNetwork()throws Exception{
  JsonNode primary=Catalog.DATA.path("featured").path("primary");assertEquals(6,primary.size());
  for(JsonNode provider:primary){String uid=provider.path("general").asText();String[] parts=uid.split("\\|");JsonNode m=Catalog.find(parts[0],parts[1]);assertEquals(Set.of("text","image"),new HashSet<>(ModelGateway.JSON.convertValue(m.path("inputs"),List.class)));ModelGateway.Prepared prepared=ModelGateway.prepare(chat(m),false);assertEquals(parts[1],prepared.model);JsonNode body=prepared.body;assertFalse(body.has("temperature"));assertFalse(body.has("top_p"));assertFalse(body.has("top_k"));if(parts[0].equals("anthropic")){assertEquals(4096,body.path("max_tokens").asInt());assertFalse(body.has("thinking"));}if(parts[0].equals("gemini"))assertTrue(body.path("contents").get(0).path("parts").get(1).has("inlineData"));}
 }
 @Test void imagePickUsesExistingGenerationSchemaOnly()throws Exception{
  MediaGateway gateway=new MediaGateway((method,url,key,type,bytes)->{assertEquals("https://api.openai.com/v1/images/generations",url);JsonNode b=ModelGateway.JSON.readTree(bytes);assertEquals("gpt-image-2.6-sunburst",b.path("model").asText());assertEquals("offline",b.path("prompt").asText());assertEquals("high",b.path("quality").asText());return new MediaGateway.Response(200,"application/json","{\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}".getBytes());});
  ObjectNode r=ModelGateway.JSON.createObjectNode().put("provider","openai").put("model","gpt-image-2.6-sunburst").put("apiKey","offline-key").put("task","image").put("prompt","offline");r.putObject("parameters").put("quality","high");gateway.run(r);
  r.put("task","video");assertThrows(ModelGateway.Problem.class,()->gateway.run(r));
 }
 @Test void noUnintegratedVideoOrOtherProviderMediaClaim(){for(JsonNode picks:Catalog.DATA.path("featured").path("primary")){assertFalse(picks.has("video"));}assertEquals("openai|gpt-realtime-2.1",Catalog.DATA.path("featured").path("primary").path("openai").path("voice").asText());assertNotNull(Catalog.find("openai","text-embedding-3-large"));}

 @Test void secondaryModelsAreExplicitDefaultsAndPrepareOffline() throws Exception {
  JsonNode f=Catalog.DATA.path("featured"),secondary=f.path("secondary"),defaults=f.path("preferredDefault");
  for(String provider:List.of("openai","anthropic","gemini","qwen")){
   String uid=secondary.path(provider).path("general").asText();assertFalse(uid.isBlank());assertEquals(uid,defaults.path(provider).path("general").asText());
   String[] parts=uid.split("\\|");JsonNode model=Catalog.find(parts[0],parts[1]);assertEquals("secondary",model.path("selectionTier").asText());
   assertEquals(parts[1],ModelGateway.prepare(chat(model),false).model);
  }
  assertEquals("openai|gpt-image-2.6-flare",defaults.path("openai").path("image").asText());
  assertEquals("openai|gpt-realtime-2.1-mini",defaults.path("openai").path("voice").asText());
  JsonNode rt=Catalog.find("openai","gpt-realtime-2.1-mini");assertEquals("realtime",RealtimeBridge.sessionConfig(rt,"").path("session").path("type").asText());
 }
 @Test void imageAlternativeUsesReviewedGenerationEndpointWithoutNetwork() throws Exception {
  MediaGateway gateway=new MediaGateway((method,url,key,type,bytes)->{assertEquals("https://api.openai.com/v1/images/generations",url);assertEquals("gpt-image-2.6-flare",ModelGateway.JSON.readTree(bytes).path("model").asText());return new MediaGateway.Response(200,"application/json","{\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}".getBytes());});
  ObjectNode r=ModelGateway.JSON.createObjectNode().put("provider","openai").put("model","gpt-image-2.6-flare").put("apiKey","offline-key").put("task","image").put("prompt","offline");gateway.run(r);
 }
}
