package com.starhub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class XaiVoiceContractTest {
 ObjectNode request(){ObjectNode r=ModelGateway.JSON.createObjectNode().put("provider","xai").put("model","voice-tts").put("task","speech").put("apiKey","offline-key").put("prompt","你好").put("voice","eve");r.putObject("parameters").put("speed",1.1);return r;}
 @Test void usesOfficialServiceContractWithoutPretendingToBeModel()throws Exception {
  AtomicInteger calls=new AtomicInteger();var gateway=new MediaGateway((method,url,key,type,body)->{calls.incrementAndGet();assertEquals("POST",method);assertEquals("https://api.x.ai/v1/tts",url);assertEquals("offline-key",key);assertEquals("application/json",type);JsonNode x=ModelGateway.JSON.readTree(body);assertEquals("你好",x.path("text").asText());assertEquals("eve",x.path("voice_id").asText());assertEquals("auto",x.path("language").asText());assertEquals(1.1,x.path("speed").asDouble());assertFalse(x.has("model"));assertFalse(x.has("apiKey"));return new MediaGateway.Response(200,"audio/mpeg",new byte[]{1,2,3});});
  var out=gateway.run(request());assertEquals("data:audio/mpeg;base64,AQID",out.path("media").path(0).path("url").asText());assertEquals(1,calls.get());
 }
 @Test void refusesBadInputsAndLimitsResponseWithoutRetry()throws Exception{
  AtomicInteger calls=new AtomicInteger();var g=new MediaGateway((a,b,c,d,e)->{calls.incrementAndGet();return new MediaGateway.Response(200,"audio/mpeg",new byte[16*1024*1024+1]);});
  ObjectNode badVoice=request();badVoice.put("voice","unauthorized");assertThrows(ModelGateway.Problem.class,()->g.run(badVoice));assertEquals(0,calls.get());ObjectNode invalid=request();invalid.putObject("parameters").put("speed",2);assertThrows(ModelGateway.Problem.class,()->g.run(invalid));assertEquals(0,calls.get());assertThrows(ModelGateway.Problem.class,()->g.run(request()));assertEquals(1,calls.get());
 }
 @Test void timeoutIsReportedWithoutAutomaticResend(){AtomicInteger calls=new AtomicInteger();var g=new MediaGateway((a,b,c,d,e)->{calls.incrementAndGet();throw new SocketTimeoutException();});assertThrows(SocketTimeoutException.class,()->g.run(request()));assertEquals(1,calls.get());}
 @Test void outboundScopeAndLegacyModelsAreRejected(){assertDoesNotThrow(()->ApiRoutes.requireOfficial(URI.create("https://api.x.ai/v1/tts")));for(String bad:new String[]{"https://api.x.ai.evil.test/v1/tts","http://api.x.ai/v1/tts","https://api.x.ai:444/v1/tts","https://localhost/v1/tts"})assertThrows(ModelGateway.Problem.class,()->ApiRoutes.requireOfficial(URI.create(bad)));assertThrows(ModelGateway.Problem.class,()->Catalog.find("openai","gpt-4o-mini-tts"));}
}
