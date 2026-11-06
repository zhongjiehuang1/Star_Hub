package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatStreamTest {
    ObjectNode request(String provider){return new ModelGatewayTest().request(provider);}
    static String event(String json){return "data: "+json+"\r\n\r\n";}
    static class Capture implements ChatStream.Sink {
        StringBuilder text=new StringBuilder();Map<String,ObjectNode> last=new HashMap<>();int deltas;
        public void emit(String name,ObjectNode value){last.put(name,value);if(name.equals("delta")){deltas++;text.append(value.path("text").asText());}}
    }
    Capture run(ObjectNode req,String content,String type)throws Exception{
        Capture out=new Capture();
        new ChatStream((p,h)->h.accept(200,type,new FilterInputStream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))){
            public int read(byte[] b,int off,int len)throws IOException{return super.read(b,off,Math.min(1,len));}
        })).run(ModelGateway.prepare(req,true),out);return out;
    }
    @Test void compatibleProvidersSendStreamingAndParseUtf8UsageWithoutReasoning()throws Exception{
        for(String provider:new String[]{"openai","deepseek","qwen","moonshot","xai"}){
            ObjectNode req=request(provider);assertTrue(ModelGateway.prepare(req,true).body.path("stream").asBoolean());
            Capture out=run(req,": keepalive\r\n"+event("{\"choices\":[{\"delta\":{\"content\":\"你好 👋\",\"reasoning_content\":\"private\"}}]}")+
                event("{\"choices\":[{\"delta\":{\"content\":\"\\n第二行\"},\"finish_reason\":\"stop\"}]}")+event("{\"choices\":[],\"usage\":{\"total_tokens\":7}}")+event("[DONE]"),"text/event-stream;charset=utf-8");
            assertEquals("你好 👋\n第二行",out.text.toString());assertEquals(2,out.deltas);assertEquals(7,out.last.get("done").path("usage").path("total_tokens").asInt());
            assertFalse(out.last.toString().contains("test-key"));assertFalse(out.last.toString().contains("private"));
        }
    }
    @Test void responsesUsesDeltaAndCompletedWithoutDuplicatingOutput()throws Exception{
        ObjectNode req=request("openai");req.put("model",Catalog.DATA.path("models").findValuesAsText("id").stream().filter(s->s.startsWith("gpt-5")).findFirst().orElseThrow());
        ModelGateway.Prepared p=ModelGateway.prepare(req,true);assertTrue(p.uri.toString().endsWith("/responses"));assertTrue(p.body.path("stream").asBoolean());assertFalse(p.body.path("store").asBoolean(true));
        Capture out=run(req,event("{\"type\":\"response.output_text.delta\",\"delta\":\"回答\"}")+event("{\"type\":\"response.completed\",\"response\":{\"usage\":{\"output_tokens\":2},\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"回答\"}]}]}}"),"text/event-stream");
        assertEquals("回答",out.text.toString());assertEquals(2,out.last.get("done").path("usage").path("output_tokens").asInt());
    }
    @Test void anthropicHandlesTextBlocksAndUsage()throws Exception{
        Capture out=run(request("anthropic"),event("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}")+event("{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"text\",\"text\":\"一\"}}")+event("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private\"}}")+event("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"二\"}}")+event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":2}}")+event("{\"type\":\"message_stop\"}"),"text/event-stream");
        assertEquals("一二",out.text.toString());assertTrue(out.last.get("done").has("warning"));assertEquals(3,out.last.get("done").path("usage").path("input_tokens").asInt());
    }
    @Test void geminiUsesStreamEndpointAndAllowsFinishAtEof()throws Exception{
        ModelGateway.Prepared p=ModelGateway.prepare(request("gemini"),true);assertTrue(p.uri.toString().endsWith(":streamGenerateContent?alt=sse"));assertFalse(p.body.has("stream"));assertEquals("test-key",p.headers.get("x-goog-api-key"));
        Capture out=run(request("gemini"),event("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"private\",\"thought\":true},{\"text\":\"回答\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"totalTokenCount\":5}}"),"text/event-stream");
        assertEquals("回答",out.text.toString());assertEquals(5,out.last.get("done").path("usage").path("totalTokenCount").asInt());
    }
    @Test void jsonFallbackRemainsProgressiveAtClientBoundary()throws Exception{
        Capture out=run(request("deepseek"),"{\"choices\":[{\"message\":{\"content\":\"回答\"}}]}","application/json");assertEquals("回答",out.text.toString());assertNotNull(out.last.get("done"));assertFalse(out.last.get("done").has("text"));
    }
    @Test void prematureEofAndProviderErrorsDoNotReportSuccessOrExposeRawDetails()throws Exception{
        for(String end:new String[]{"",event("{\"error\":{\"message\":\"secret-credential\"}}"),event("broken-json")}){
            Capture capture=new Capture();String input=event("{\"choices\":[{\"delta\":{\"content\":\"部分\"}}]}")+end;
            ChatStream stream=new ChatStream((p,h)->h.accept(200,"text/event-stream",new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))));
            ModelGateway.Problem error=assertThrows(ModelGateway.Problem.class,()->stream.run(ModelGateway.prepare(request("deepseek"),true),capture));
            assertEquals("部分",capture.text.toString());assertNull(capture.last.get("done"));assertFalse(error.getMessage().contains("secret-credential"));
        }
    }
    @Test void httpErrorsPrecedeAnySseOutputAndClientDisconnectStopsReading()throws Exception{
        Capture capture=new Capture();
        ChatStream stream=new ChatStream((p,h)->h.accept(401,"application/json",new ByteArrayInputStream("secret".getBytes())));
        assertThrows(ModelGateway.Problem.class,()->stream.run(ModelGateway.prepare(request("deepseek"),true),capture));assertTrue(capture.last.isEmpty());
        ChatStream normal=new ChatStream((p,h)->h.accept(200,"text/event-stream",new ByteArrayInputStream(event("[DONE]").getBytes())));
        assertThrows(IOException.class,()->normal.run(ModelGateway.prepare(request("deepseek"),true),(e,d)->{throw new IOException("disconnected");}));
    }
    @Test void everyCatalogModelHasCredentialFreeRoutesMatchingTransportBuilders()throws Exception{
        int count=0;
        for(JsonNode m:Catalog.DATA.path("models")){
            for(JsonNode category:m.path("categories")){
                String task=category.asText();if(task.equals("vision"))continue;
                JsonNode routes=m.path("apiRoutes").path(task);assertTrue(routes.isArray()&&!routes.isEmpty(),m.path("id").asText()+"/"+task);
                String expected=m.path("batch").asBoolean()?ApiRoutes.files():task.equals("realtime")?ApiRoutes.realtime(m.path("id").asText()):task.equals("chat")?ApiRoutes.chat(ModelGateway.PROVIDERS.get(m.path("provider").asText()),m.path("id").asText(),true):ApiRoutes.media(m,task,"");
                assertEquals(expected,routes.path(0).path("url").asText());assertFalse(expected.contains("test-key"));count++;
            }
        }
        assertTrue(count>=60);
        for(JsonNode p:Catalog.DATA.path("providers"))for(String key:new String[]{"website","keyUrl","docsUrl","baseUrl"})assertTrue(p.path(key).asText().startsWith("https://"),p.path("id").asText()+"/"+key);
    }
    @Test void searchCitationsSurviveStreamingAndJsonFallback() throws Exception {
        ObjectNode req=request("openai").put("model","gpt-6.1-sol").put("webSearch",true);
        String annotation="{\"type\":\"url_citation\",\"url\":\"https://example.com\",\"title\":\"Source\",\"start_index\":0,\"end_index\":2}";
        Capture stream=run(req,event("{\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}")+event("{\"type\":\"response.output_text.annotation.added\",\"annotation\":"+annotation+"}")+event("{\"type\":\"response.completed\",\"response\":{}}"),"text/event-stream");
        assertEquals("https://example.com",stream.last.get("done").path("details").path("webCitations").path(0).path("url").asText());
        Capture json=run(req,"{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"OK\",\"annotations\":["+annotation+"]}]}]}","application/json");
        assertEquals("Source",json.last.get("done").path("details").path("webCitations").path(0).path("title").asText());
    }
    @Test void publicReasoningSummaryStreamsSeparatelyFromAnswer() throws Exception {
        ObjectNode req=request("openai").put("model","gpt-6.1-sol");
        assertEquals("auto",ModelGateway.prepare(req,true).body.path("reasoning").path("summary").asText());
        Capture out=run(req,event("{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"Public summary\"}")+event("{\"type\":\"response.output_text.delta\",\"delta\":\"Answer\"}")+event("{\"type\":\"response.completed\",\"response\":{}}"),"text/event-stream");
        assertEquals("Public summary",out.last.get("reasoning").path("text").asText());assertEquals("Answer",out.text.toString());
    }
    @Test void providerSearchEvidenceSurvivesStreaming() throws Exception {
        ObjectNode deep=request("deepseek").put("model","deepseek-v4-pro").put("webSearch",true);
        Capture d=run(deep,event("{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"web_search_call\",\"action\":{\"sources\":[{\"url\":\"https://example.org\",\"title\":\"Source\"}]}}}")+event("{\"type\":\"response.output_text.delta\",\"delta\":\"Done\"}")+event("{\"type\":\"response.completed\",\"response\":{}}"),"text/event-stream");
        assertTrue(d.last.get("done").path("details").path("webSearchUsed").asBoolean());assertEquals("https://example.org",d.last.get("done").path("details").path("webCitations").path(0).path("url").asText());
        ObjectNode claude=request("anthropic").put("model","claude-opus-5-5").put("webSearch",true);
        Capture a=run(claude,event("{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"server_tool_use\",\"name\":\"web_search\"}}")+event("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Answer\"}}")+event("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"type\":\"web_search_result_location\",\"url\":\"https://example.com\",\"title\":\"Citation\"}}}")+event("{\"type\":\"message_stop\"}"),"text/event-stream");
        assertTrue(a.last.get("done").path("details").path("webSearchUsed").asBoolean());assertEquals("Citation",a.last.get("done").path("details").path("webCitations").path(0).path("title").asText());
        ObjectNode gemini=request("gemini").put("model","gemini-3.8-flash").put("webSearch",true);
        Capture g=run(gemini,event("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Answer\"}]},\"finishReason\":\"STOP\",\"groundingMetadata\":{\"webSearchQueries\":[\"q\"],\"searchEntryPoint\":{\"renderedContent\":\"<p>suggestions</p>\"},\"groundingChunks\":[{\"web\":{\"uri\":\"https://example.net\",\"title\":\"Site\"}}]}}]}"),"text/event-stream");
        assertTrue(g.last.get("done").path("details").path("webSearchUsed").asBoolean());assertTrue(g.last.get("done").path("details").path("googleWidget").asText().contains("suggestions"));
    }
}
