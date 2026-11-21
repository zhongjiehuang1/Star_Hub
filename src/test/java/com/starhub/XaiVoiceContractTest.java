package com.starhub;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class XaiVoiceContractTest {
 @Test void removedXaiVoiceCannotDispatch() {
  var r=ModelGateway.JSON.createObjectNode().put("provider","xai").put("model","voice-tts").put("task","speech").put("apiKey","offline-key").put("prompt","hello");
  assertThrows(ModelGateway.Problem.class,()->new MediaGateway((a,b,c,d,e)->{throw new AssertionError("unexpected upstream call");}).run(r));
 }
}
