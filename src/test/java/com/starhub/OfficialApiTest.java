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
        assertEquals(Set.of("openai","anthropic","gemini","deepseek","qwen","moonshot"),ModelGateway.PROVIDERS.keySet());
        String encoded=Catalog.DATA.toString().toLowerCase();for(String excluded:List.of("openrouter","pollinations","groq","siliconflow"))assertFalse(encoded.contains(excluded));
        Map<String,String> makers=Map.of("openai","openai","anthropic","anthropic","gemini","google","deepseek","deepseek","qwen","alibaba","moonshot","moonshotai");
        Set<String> ids=new HashSet<>();
        for(JsonNode m:Catalog.DATA.path("models")){
            assertTrue(ids.add(m.path("uid").asText()));assertEquals(makers.get(m.path("provider").asText()),m.path("vendor").asText());
            assertTrue(m.path("requiresKey").asBoolean());assertFalse(m.path("description").asText().isBlank());assertTrue(m.path("source").asText().startsWith("https://"));
        }
        assertTrue(ids.size() >= Catalog.publicData().path("models").size());
    }
    @Test void removedProvidersCannotReachChatMediaBatchOrRealtime(){
        for(String removed:List.of("openrouter","pollinations","groq","siliconflow","custom","xai")){
            ObjectNode r=request("gpt-6.2-sol","chat").put("provider",removed);
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
        ObjectNode r=request("gpt-image-2.6-sunburst","image").put("baseUrl","https://evil.example");r.putObject("parameters").put("size","1024x1024").put("quality","high");
        MediaGateway g=new MediaGateway((method,url,key,type,bytes)->{assertEquals("https://api.openai.com/v1/images/generations",url);JsonNode body=ModelGateway.JSON.readTree(bytes);assertEquals("1024x1024",body.path("size").asText());assertFalse(body.has("apiKey"));assertFalse(body.has("baseUrl"));return response("{\"data\":[{\"b64_json\":\"AQID\"}]}");});
        JsonNode generated=g.run(r).path("media").path(0);assertArrayEquals(new byte[]{1,2,3},generated.path("imageBytes").binaryValue());assertEquals("image/png",generated.path("mime").asText());assertFalse(generated.has("url"));
        r.put("file","data:image/png;base64,AQID");assertThrows(ModelGateway.Problem.class,()->g.run(r));
    }
    @Test void explicitImageEditUsesOfficialEndpointAndBoundedSource()throws Exception{
        ObjectNode r=request("gpt-image-2.6-flare","image").put("imageEdit",true).put("file","data:image/png;base64,AQID");
        r.putObject("parameters").put("size","1024x1024").put("quality","high");
        int[] calls={0};MediaGateway g=new MediaGateway((method,url,key,type,bytes)->{
            calls[0]++;assertEquals("POST",method);assertEquals("https://api.openai.com/v1/images/edits",url);assertEquals("application/json",type);
            JsonNode body=ModelGateway.JSON.readTree(bytes);assertEquals("gpt-image-2.6-flare",body.path("model").asText());assertEquals("data:image/png;base64,AQID",body.path("images").path(0).path("image_url").asText());
            assertEquals(1,body.path("images").size());assertEquals("1024x1024",body.path("size").asText());assertFalse(body.has("apiKey"));assertFalse(body.has("imageEdit"));
            return response("{\"data\":[{\"b64_json\":\"AQID\"}]}");
        });
        assertArrayEquals(new byte[]{1,2,3},g.run(r).path("media").path(0).path("imageBytes").binaryValue());assertEquals(1,calls[0]);
        for(String invalid:List.of("", "https://example.com/image.png", "data:image/svg+xml;base64,AQID", "data:image/png;base64,???", "data:image/png;base64,"+"A".repeat(6*1024*1024))){
            r.put("file",invalid);assertThrows(ModelGateway.Problem.class,()->g.run(r));
        }
        r.put("file","data:image/png;base64,AQID").put("imageEdit","true");assertThrows(ModelGateway.Problem.class,()->g.run(r));
        r.put("imageEdit",true).putArray("files").add("data:image/png;base64,AQID");assertThrows(ModelGateway.Problem.class,()->g.run(r));assertEquals(1,calls[0]);
    }
    @Test void imageResponseSettingsSkipLargeSourceWithoutChangingOutputLimits()throws Exception{
        ObjectNode body=ModelGateway.JSON.createObjectNode().put("model","gpt-image-2.6-flare");
        body.putArray("images").addObject().put("image_url","data:image/png;base64,"+"A".repeat(4*1024*1024));
        body.put("output_format","webp").put("n",2);
        ObjectNode options=MediaGateway.imageResponseOptions(ModelGateway.JSON.writeValueAsBytes(body));
        assertEquals(2,options.path("n").asInt());assertEquals("webp",options.path("output_format").asText());assertEquals(2,options.size());
        ObjectNode defaults=MediaGateway.imageResponseOptions("{}".getBytes(StandardCharsets.UTF_8));assertEquals(1,defaults.path("n").asInt());assertEquals("png",defaults.path("output_format").asText());
        assertThrows(java.io.IOException.class,()->MediaGateway.imageResponseOptions("[]".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void imageEditAcceptsExactlyFourMibAndRejectsOneByteMore()throws Exception{
        ObjectNode r=request("gpt-image-2.6-flare","image").put("imageEdit",true);int[] calls={0};
        MediaGateway gateway=new MediaGateway((method,url,key,type,body)->{calls[0]++;assertTrue(body.length<6*1024*1024);return response("{\"data\":[{\"b64_json\":\"AQID\"}]}");});
        r.put("file","data:image/png;base64,"+Base64.getEncoder().encodeToString(new byte[4*1024*1024]));gateway.run(r);assertEquals(1,calls[0]);
        r.put("file","data:image/png;base64,"+Base64.getEncoder().encodeToString(new byte[4*1024*1024+1]));assertThrows(ModelGateway.Problem.class,()->gateway.run(r));assertEquals(1,calls[0]);
    }
    @Test void imageEditsAreLimitedToOneWhileTextGenerationStillSupportsFour()throws Exception{
        ObjectNode edit=request("gpt-image-2.6-flare","image").put("imageEdit",true).put("file","data:image/png;base64,AQID");int[] calls={0};
        MediaGateway gateway=new MediaGateway((method,url,key,type,body)->{calls[0]++;assertEquals("https://api.openai.com/v1/images/generations",url);assertEquals(4,ModelGateway.JSON.readTree(body).path("n").asInt());return response("{\"data\":[{\"b64_json\":\"AQID\"},{\"b64_json\":\"AQID\"},{\"b64_json\":\"AQID\"},{\"b64_json\":\"AQID\"}]}");});
        for(int n:List.of(2,4)){
            edit.putObject("parameters").put("n",n);
            ModelGateway.Problem problem=assertThrows(ModelGateway.Problem.class,()->gateway.run(edit));assertEquals(400,problem.status);assertTrue(problem.getMessage().contains("只能生成一张"));
        }
        assertEquals(0,calls[0]);ObjectNode generated=request("gpt-image-2.6-flare","image");generated.putObject("parameters").put("n",4);
        assertEquals(4,gateway.run(generated).path("media").size());assertEquals(1,calls[0]);
    }
    @Test void imageModerationIsReportedWithoutForwardingProviderDetails(){
        ObjectNode r=request("gpt-image-2.6-sunburst","image");
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
        ObjectNode r=request("gpt-transcribe","transcription").put("file","data:audio/wav;base64,AQID").put("fileFormat","wav");r.putObject("parameters");
        assertEquals("你好",new MediaGateway((method,url,key,type,bytes)->{assertEquals("https://api.openai.com/v1/audio/transcriptions",url);assertTrue(type.startsWith("multipart/form-data"));String raw=new String(bytes,StandardCharsets.UTF_8);assertTrue(raw.contains("gpt-transcribe"));assertFalse(raw.contains("test-key"));return response("{\"text\":\"你好\"}");}).run(r).path("text").asText());
        ObjectNode e=request("text-embedding-3-large","embeddings");e.putObject("parameters").put("dimensions",512);
        new MediaGateway((a,url,c,d,bytes)->{assertEquals("https://api.openai.com/v1/embeddings",url);assertEquals(512,ModelGateway.JSON.readTree(bytes).path("dimensions").asInt());return response("{\"data\":[{\"embedding\":[0.1]}]}");}).run(e);
    }
    @Test void batchUploadUsesNativeJsonlThenReferencesOfficialFileId()throws Exception{
        for(String model:List.of("gpt-6.2-sol","text-embedding-3-large")){
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
        });JsonNode job=g.run(request("gpt-6.2-sol:batch","chat").put("action","get").put("jobId","batch_123")).path("job");assertEquals(3,calls.get());assertEquals("成功",job.path("results").path(0).path("text").asText());assertFalse(job.toString().contains("secret-value"));assertEquals(2,job.path("results").size());
    }
    @Test void batchRejectsInvalidInputsAndDoesNotRetryFailedUpload(){
        ObjectNode r=request("gpt-6.2-sol:batch","chat");r.putArray("inputs").add("x");AtomicInteger calls=new AtomicInteger();BatchGateway g=new BatchGateway((a,b,c,d,e)->{calls.incrementAndGet();return new MediaGateway.Response(401,"application/json","secret".getBytes());});
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
