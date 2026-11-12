package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OfficialApiTest {
    ObjectNode request(String model,String task){return new ParameterGatewayTest().req("openai",model,task);}
    MediaGateway.Response response(String text){return new MediaGateway.Response(200,"application/json",text.getBytes(StandardCharsets.UTF_8));}
    @Test void catalogAndProviderListContainOnlyManufacturerOwnedIntegrations(){
        assertEquals(Set.of("openai","anthropic","gemini","deepseek","qwen","moonshot","xai"),ModelGateway.PROVIDERS.keySet());
        String encoded=Catalog.DATA.toString().toLowerCase();for(String excluded:List.of("openrouter","pollinations","groq","siliconflow"))assertFalse(encoded.contains(excluded));
        Map<String,String> makers=Map.of("openai","openai","anthropic","anthropic","gemini","google","deepseek","deepseek","qwen","alibaba","moonshot","moonshotai","xai","x-ai");
        Set<String> ids=new HashSet<>();
        for(JsonNode m:Catalog.DATA.path("models")){
            assertTrue(ids.add(m.path("uid").asText()));assertEquals(makers.get(m.path("provider").asText()),m.path("vendor").asText());
            assertTrue(m.path("requiresKey").asBoolean());assertFalse(m.path("description").asText().isBlank());assertTrue(m.path("source").asText().startsWith("https://"));
        }
        assertTrue(ids.size() >= Catalog.publicData().path("models").size());
    }
    @Test void removedProvidersCannotReachChatMediaBatchOrRealtime(){
        for(String removed:List.of("openrouter","pollinations","groq","siliconflow","custom")){
            ObjectNode r=request("gpt-4.1-mini","chat").put("provider",removed);
            assertThrows(ModelGateway.Problem.class,()->new ModelGateway((a,b,c)->{throw new AssertionError();}).chat(r));
            assertThrows(ModelGateway.Problem.class,()->new MediaGateway((a,b,c,d,e)->{throw new AssertionError();}).run(r));
            assertThrows(ModelGateway.Problem.class,()->new BatchGateway((a,b,c,d,e)->{throw new AssertionError();}).run(r));
            RealtimeBridge bridge=new RealtimeBridge(new RealtimeBridge.Downstream(){public java.util.concurrent.CompletionStage<Void> send(String s){return CompletableFuture.completedFuture(null);}public void close(){}},(a,b,c,d,e)->{throw new AssertionError();});
            r.put("type","starhub.connect").put("model","gpt-realtime-2.1");bridge.receive(r.toString());
        }
    }
    @Test void outboundWhitelistRejectsForeignHostsAndMalformedOfficialUrls(){
        for(String bad:List.of("http://api.openai.com/v1/chat/completions","https://openrouter.ai/api/v1/chat/completions","https://api.openai.com.evil.example/v1/chat/completions","https://test@api.openai.com/v1/chat/completions","https://api.openai.com:8443/v1/chat/completions","https://127.0.0.1/v1/chat/completions"))assertThrows(ModelGateway.Problem.class,()->ApiRoutes.requireOfficial(URI.create(bad)));
        for(ModelGateway.Provider p:ModelGateway.PROVIDERS.values())assertDoesNotThrow(()->ApiRoutes.requireOfficial(URI.create(ApiRoutes.chat(p,p.example,true))));
        assertTrue(ApiRoutes.realtime("gpt-realtime-2.1").startsWith("wss://api.openai.com/v1/realtime?model="));
    }
    @Test void nativeImageUsesOfficialEndpointAndRejectsUnimplementedAttachments()throws Exception{
        ObjectNode r=request("gpt-image-2.5-sunburst","image").put("baseUrl","https://evil.example");r.putObject("parameters").put("size","1024x1024").put("quality","high");
        MediaGateway g=new MediaGateway((method,url,key,type,bytes)->{assertEquals("https://api.openai.com/v1/images/generations",url);JsonNode body=ModelGateway.JSON.readTree(bytes);assertEquals("1024x1024",body.path("size").asText());assertFalse(body.has("apiKey"));assertFalse(body.has("baseUrl"));return response("{\"data\":[{\"b64_json\":\"AQID\"}]}");});
        JsonNode generated=g.run(r).path("media").path(0);assertArrayEquals(new byte[]{1,2,3},generated.path("imageBytes").binaryValue());assertEquals("image/png",generated.path("mime").asText());assertFalse(generated.has("url"));
        r.put("file","data:image/png;base64,AQID");assertThrows(ModelGateway.Problem.class,()->g.run(r));
    }
    @Test void nativeSpeechPreservesVoiceSettingsAndReturnsPlayableAudio()throws Exception{
        ObjectNode r=request("gpt-4o-mini-tts","speech").put("voice","coral");r.putObject("parameters").put("instructions","轻声").put("speed",1.1);
        JsonNode out=new MediaGateway((method,url,key,type,bytes)->{assertEquals("https://api.openai.com/v1/audio/speech",url);JsonNode b=ModelGateway.JSON.readTree(bytes);assertEquals("coral",b.path("voice").asText());assertEquals("轻声",b.path("instructions").asText());return new MediaGateway.Response(200,"audio/mpeg",new byte[]{1,2,3});}).run(r);
        assertEquals("data:audio/mpeg;base64,AQID",out.path("media").path(0).path("url").asText());
    }
    @Test void imageModerationIsReportedWithoutForwardingProviderDetails(){
        ObjectNode r=request("gpt-image-2.5-sunburst","image");
        MediaGateway blocked=new MediaGateway((a,b,c,d,e)->new MediaGateway.Response(400,"application/json","{\"error\":{\"code\":\"moderation_blocked\",\"message\":\"secret-value\"}}".getBytes(StandardCharsets.UTF_8)));
        ModelGateway.Problem problem=assertThrows(ModelGateway.Problem.class,()->blocked.run(r));
        assertEquals(400,problem.status);assertTrue(problem.getMessage().contains("内容审核"));assertFalse(problem.getMessage().contains("secret-value"));
        for(String body:List.of("not-json","{\"error\":{\"code\":\"invalid_parameter\",\"message\":\"secret-value\"}}")){
            MediaGateway other=new MediaGateway((a,b,c,d,e)->new MediaGateway.Response(400,"application/json",body.getBytes(StandardCharsets.UTF_8)));
            ModelGateway.Problem fallback=assertThrows(ModelGateway.Problem.class,()->other.run(r));
            assertTrue(fallback.getMessage().contains("HTTP 400"));assertFalse(fallback.getMessage().contains("secret-value"));
        }
    }
    @Test void transcriptionMultipartCarriesNativeFieldsAndEmbeddingsKeepDimensions()throws Exception{
        ObjectNode r=request("gpt-4o-mini-transcribe","transcription").put("file","data:audio/wav;base64,AQID").put("fileFormat","wav");r.putObject("parameters").put("language","zh").put("transcription_prompt","专有词");
        assertEquals("你好",new MediaGateway((method,url,key,type,bytes)->{assertEquals("https://api.openai.com/v1/audio/transcriptions",url);assertTrue(type.startsWith("multipart/form-data"));String raw=new String(bytes,StandardCharsets.UTF_8);assertTrue(raw.contains("name=\"language\"\r\n\r\nzh"));assertTrue(raw.contains("name=\"prompt\"\r\n\r\n专有词"));assertFalse(raw.contains("test-key"));return response("{\"text\":\"你好\"}");}).run(r).path("text").asText());
        ObjectNode e=request("text-embedding-3-small","embeddings");e.putObject("parameters").put("dimensions",512);
        new MediaGateway((a,url,c,d,bytes)->{assertEquals("https://api.openai.com/v1/embeddings",url);assertEquals(512,ModelGateway.JSON.readTree(bytes).path("dimensions").asInt());return response("{\"data\":[{\"embedding\":[0.1]}]}");}).run(e);
    }
    @Test void batchUploadUsesNativeJsonlThenReferencesOfficialFileId()throws Exception{
        for(String model:List.of("gpt-4.1-mini","text-embedding-3-small")){
            AtomicInteger calls=new AtomicInteger();ObjectNode r=request(model+":batch",model.startsWith("text-")?"embeddings":"chat");r.putArray("inputs").add("第一条").add("第二条");r.putObject("parameters").put(model.startsWith("text-")?"dimensions":"max_tokens",512);
            BatchGateway gateway=new BatchGateway((method,url,key,type,bytes)->{
                assertEquals("POST",method);assertFalse(new String(bytes,StandardCharsets.UTF_8).contains("test-key"));
                if(calls.getAndIncrement()==0){assertEquals(ApiRoutes.files(),url);String raw=new String(bytes,StandardCharsets.UTF_8);assertTrue(raw.contains("name=\"purpose\"\r\n\r\nbatch"));String rows=raw.substring(raw.indexOf("{\"custom_id\""),raw.lastIndexOf("\r\n--"));String[] lines=rows.strip().split("\n");assertEquals(2,lines.length);JsonNode row=ModelGateway.JSON.readTree(lines[0]);assertEquals(model,row.path("body").path("model").asText());assertEquals("POST",row.path("method").asText());assertEquals(model.startsWith("text-")?"/v1/embeddings":"/v1/chat/completions",row.path("url").asText());assertEquals(512,row.path("body").path(model.startsWith("text-")?"dimensions":"max_completion_tokens").asInt());return response("{\"id\":\"file_123\"}");}
                assertEquals(ApiRoutes.batch(),url);JsonNode body=ModelGateway.JSON.readTree(bytes);assertEquals("file_123",body.path("input_file_id").asText());assertFalse(body.has("requests"));return response("{\"id\":\"batch_123\",\"status\":\"validating\"}");
            });assertEquals("batch_123",gateway.run(r).path("job").path("id").asText());assertEquals(2,calls.get());
        }
    }
    @Test void completedBatchReadsOutputAndErrorFilesWithoutResubmitting()throws Exception{
        AtomicInteger calls=new AtomicInteger();BatchGateway g=new BatchGateway((method,url,key,type,bytes)->{
            calls.incrementAndGet();assertEquals("GET",method);
            if(url.endsWith("/batch_123"))return response("{\"id\":\"batch_123\",\"status\":\"completed\",\"output_file_id\":\"file_ok\",\"error_file_id\":\"file_error\",\"request_counts\":{\"completed\":1,\"failed\":1,\"total\":2}}");
            if(url.endsWith("/file_ok/content"))return response("{\"custom_id\":\"task-001\",\"response\":{\"body\":{\"choices\":[{\"message\":{\"content\":\"成功\"}}]}}}\n");
            assertTrue(url.endsWith("/file_error/content"));return response("{\"custom_id\":\"task-002\",\"error\":{\"message\":\"secret-value\"}}\n");
        });JsonNode job=g.run(request("gpt-4.1-mini:batch","chat").put("action","get").put("jobId","batch_123")).path("job");assertEquals(3,calls.get());assertEquals("成功",job.path("results").path(0).path("text").asText());assertFalse(job.toString().contains("secret-value"));assertEquals(2,job.path("results").size());
    }
    @Test void batchRejectsInvalidInputsAndDoesNotRetryFailedUpload(){
        ObjectNode r=request("gpt-4.1-mini:batch","chat");r.putArray("inputs").add("x");AtomicInteger calls=new AtomicInteger();BatchGateway g=new BatchGateway((a,b,c,d,e)->{calls.incrementAndGet();return new MediaGateway.Response(401,"application/json","secret".getBytes());});
        assertThrows(ModelGateway.Problem.class,()->g.run(r));assertEquals(1,calls.get());r.put("action","get").put("jobId","../secret");assertThrows(ModelGateway.Problem.class,()->g.run(r));assertEquals(1,calls.get());
    }
    @Test void publishedSourceMatchesBuiltFilesAndNeverAcceptsPaths()throws Exception{
        JsonNode manifest=CallSources.read(null);assertTrue(manifest.path("files").size()>10);
        for(JsonNode entry:manifest.path("files")){
            String name=entry.path("name").asText();JsonNode file=CallSources.read(name);
            Path source=Path.of(name.startsWith("server/")?"src/main/java/com/starhub/"+name.substring(7):"src/main/webapp/"+name.substring(8));
            assertEquals(Files.readString(source),file.path("content").asText());assertEquals(entry.path("sha256"),file.path("sha256"));assertEquals(64,file.path("sha256").asText().length());
        }
        for(String path:List.of("../../etc/passwd","/etc/passwd","server/../../../.env","server/ModelGateway.java?apiKey=secret"))assertEquals(404,assertThrows(ModelGateway.Problem.class,()->CallSources.read(path)).status);
    }
}
