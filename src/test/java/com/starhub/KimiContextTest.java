package com.starhub;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class KimiContextTest {
 final byte[] key=new byte[32];
 final String owner="account:7:generationA:tabA",model="kimi-k3.1-pro",answer="answer",reason="private continuation";
 ObjectNode request(String token) {
  ObjectNode q=ModelGateway.JSON.createObjectNode().put("provider","moonshot").put("model",model);
  ArrayNode a=q.putArray("messages");a.addObject().put("role","user").put("content","question");
  ObjectNode m=a.addObject().put("role","assistant").put("content",answer).put("provider","moonshot").put("model",model);
  if(token!=null)m.putObject("providerContext").put("provider","moonshot").put("model",model).put("token",token);
  a.addObject().put("role","user").put("content","continue");return q;
 }
 @Test void opaqueContextRoundTripsOnlyForOwnerGenerationModelAndAnswer()throws Exception {
  String token=KimiContext.seal(key,owner,model,answer,reason);assertTrue(token.matches("[A-Za-z0-9_-]+"));
  assertFalse(new String(Base64.getUrlDecoder().decode(token),StandardCharsets.UTF_8).contains(reason));
  // Simulate the same vault bytes loaded by a second JVM; a distinct nonce changes each seal.
  assertEquals(reason,KimiContext.open(key.clone(),"account:7:generationA:otherTab",model,answer,token));
  assertNotEquals(token,KimiContext.seal(key.clone(),owner,model,answer,reason));
  for(String different:new String[]{"account:8:generationA:tabA","account:7:generationB:tabA","guest:guestA:tabA"})assertThrows(ModelGateway.Problem.class,()->KimiContext.open(key,different,model,answer,token));
  assertThrows(ModelGateway.Problem.class,()->KimiContext.open(key,owner,"kimi-k3",answer,token));
  assertThrows(ModelGateway.Problem.class,()->KimiContext.open(key,owner,model,"edited answer",token));
  char first=token.charAt(0);String tampered=(first=='A'?'B':'A')+token.substring(1);assertThrows(ModelGateway.Problem.class,()->KimiContext.open(key,owner,model,answer,tampered));
 }
 @Test void hydrateRestoresBoundThoughtAndRejectsUntrustedPlaintext()throws Exception {
  String token=KimiContext.seal(key,owner,model,answer,reason);ObjectNode q=request(token);
  KimiContext.hydrate(q,owner,key);assertEquals(reason,q.path("messages").get(1).path("providerReasoning").path("content").asText());
  assertFalse(q.path("messages").get(1).has("providerContext"));
  ObjectNode bad=request(null);((ObjectNode)bad.path("messages").get(1)).putObject("providerReasoning").put("content","forged");
  assertThrows(ModelGateway.Problem.class,()->KimiContext.hydrate(bad,owner,key));
  ObjectNode foreign=request(token);((ObjectNode)foreign.path("messages").get(1)).put("model","kimi-k3");assertThrows(ModelGateway.Problem.class,()->KimiContext.hydrate(foreign,owner,key));
 }
 @Test void resultContainsOnlyCiphertextAndEnforcesSize()throws Exception {
  ObjectNode out=ModelGateway.JSON.createObjectNode().put("provider","moonshot").put("model",model).put("text",answer);
  out.putObject("providerReasoning").put("provider","moonshot").put("model",model).put("content",reason);
  KimiContext.protectResult(out,owner,key);assertFalse(out.toString().contains(reason));assertFalse(out.has("providerReasoning"));
  assertEquals(reason,KimiContext.open(key,owner,model,answer,out.path("providerContext").path("token").asText()));
  assertThrows(ModelGateway.Problem.class,()->KimiContext.seal(key,owner,model,answer,"x".repeat(100001)));
  assertThrows(ModelGateway.Problem.class,()->KimiContext.seal(key,owner,model,answer,"😀".repeat(25001)));
  // A large escaped value that fits the declared ciphertext limit must round-trip.
  String quotes="\"".repeat(52000);String quotedToken=KimiContext.seal(key,owner,model,answer,quotes);
  assertTrue(quotedToken.length()<=KimiContext.MAX_TOKEN);assertEquals(quotes,KimiContext.open(key,owner,model,answer,quotedToken));
  // JSON escaping can exceed the ciphertext/token limit even below the UTF-8 ceiling: never truncate.
  assertThrows(ModelGateway.Problem.class,()->KimiContext.seal(key,owner,model,answer,"\"".repeat(100000)));
  assertThrows(ModelGateway.Problem.class,()->KimiContext.seal(key,owner,model,"\ud800",reason));
  assertThrows(ModelGateway.Problem.class,()->KimiContext.seal(key,owner,model,answer,"\ud800"));
  ObjectNode tooLarge=ModelGateway.JSON.createObjectNode().put("provider","moonshot").put("model",model).put("text",answer);
  tooLarge.putObject("providerReasoning").put("provider","moonshot").put("model",model).put("content","\"".repeat(100000));
  KimiContext.protectResult(tooLarge,owner,key);assertTrue(tooLarge.path("continuationUnavailable").asBoolean());assertFalse(tooLarge.has("providerContext"));assertFalse(tooLarge.has("providerReasoning"));assertEquals(answer,tooLarge.path("text").asText());
  ObjectNode malformed=ModelGateway.JSON.createObjectNode().put("provider","moonshot").put("model",model).put("text",answer).put("providerReasoning","private invalid data");
  KimiContext.protectResult(malformed,owner,key);assertTrue(malformed.path("continuationUnavailable").asBoolean());assertFalse(malformed.has("providerReasoning"));assertEquals(answer,malformed.path("text").asText());
 }
}
