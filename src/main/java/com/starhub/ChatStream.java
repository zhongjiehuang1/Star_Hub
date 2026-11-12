package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** Bounded upstream SSE parsing. Never forwards raw provider errors or reasoning text. */
final class ChatStream {
    interface Sink { void emit(String event,ObjectNode data)throws IOException; }
    interface ResponseHandler { void accept(int status,String type,InputStream input)throws IOException; }
    interface Transport { void call(ModelGateway.Prepared request,ResponseHandler response)throws IOException; }
    private final Transport transport;
    ChatStream(){this(ChatStream::https);}
    ChatStream(Transport transport){this.transport=transport;}
    void run(ModelGateway.Prepared p,Sink sink)throws IOException{
        transport.call(p,(status,type,input)->{
            ModelGateway.checkStatus(p.provider,status);
            sink.emit("meta",ModelGateway.JSON.createObjectNode().put("endpoint",p.uri.toString()).put("provider",p.provider.id).put("model",p.model));
            if(type!=null&&type.toLowerCase().contains("application/json")){
                byte[] bytes=input.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024)throw bad("服务商响应过大。");
                ObjectNode result=ModelGateway.decode(p,bytes);
                sink.emit("delta",ModelGateway.JSON.createObjectNode().put("text",result.path("text").asText()));result.remove("text");sink.emit("done",result);return;
            }
            if(type==null||!type.toLowerCase().contains("text/event-stream"))throw bad("服务商未返回可识别的流式响应。");
            State state=new State(p,sink);StringBuilder event=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(new LimitedInput(input),StandardCharsets.UTF_8))){
                String line;while((line=reader.readLine())!=null){
                    if(System.nanoTime()-state.started>180_000_000_000L)throw bad("回复时间过长，已结束连接。");
                    if(line.isEmpty()){
                        if(event.length()>0){state.event(event.toString());event.setLength(0);}
                        if(state.done)break;
                    }else if(line.startsWith("data:")){
                        if(event.length()>0)event.append('\n');event.append(line.substring(5).stripLeading());
                        if(event.length()>512*1024)throw bad("服务商单段响应过大。");
                    }
                    if(System.nanoTime()-state.lastSent>10_000_000_000L){sink.emit("progress",ModelGateway.JSON.createObjectNode().put("status","generating"));state.lastSent=System.nanoTime();}
                }
            }
            if(event.length()>0&&!state.done)state.event(event.toString());
            if(!state.done&&!state.finished)throw bad("流式连接提前中断，已收到的文字已保留。");
            if(state.characters==0)throw bad("服务商未返回文本，可能触发内容过滤或模型不支持文本对话。");
            ObjectNode result=ModelGateway.JSON.createObjectNode().put("provider",p.provider.id).put("model",p.model);
            if(!state.usage.isEmpty())result.set("usage",state.usage);
            if(!state.details.isEmpty())result.set("details",state.details);
            if(!state.warning.isEmpty())result.put("warning",state.warning);
            sink.emit("done",result);
        });
    }
    static final class State {
        final ModelGateway.Prepared p;final Sink sink;final long started=System.nanoTime();long lastSent=started;
        final ObjectNode usage=ModelGateway.JSON.createObjectNode(),details=ModelGateway.JSON.createObjectNode();
        int characters,summaryCharacters;boolean done,finished;String warning="";
        State(ModelGateway.Prepared p,Sink sink){this.p=p;this.sink=sink;}
        void text(String value)throws IOException{
            if(value.isEmpty())return;characters+=value.length();if(characters>1000000)throw bad("回复过长，已结束连接。");
            sink.emit("delta",ModelGateway.JSON.createObjectNode().put("text",value));lastSent=System.nanoTime();
        }
        void finish(String reason){
            if(reason.isEmpty())return;finished=true;
            if(reason.equals("length")||reason.equals("max_tokens")||reason.equals("MAX_TOKENS"))warning="已达到模型输出上限，回复可能未完整。";
            else if(reason.equals("content_filter")||reason.equals("SAFETY")||reason.equals("REFUSAL"))warning="部分内容被调用平台的安全规则截断。";
        }
        void event(String data)throws IOException{
            if(data.equals("[DONE]")){done=true;return;}
            JsonNode e;
            try{e=ModelGateway.JSON.readTree(data);}catch(com.fasterxml.jackson.core.JsonProcessingException x){throw bad("服务商流式数据格式异常。");}
            if(e==null||!e.isObject())throw bad("服务商流式数据格式异常。");
            String type=e.path("type").asText();
            if(e.hasNonNull("error")||type.equals("error")||type.equals("response.failed"))throw bad("模型流式回复失败，请检查平台状态、账户权限及请求设置。");
            if(e.path("usage").isObject())usage.setAll((ObjectNode)e.path("usage"));
            if(e.has("citations"))details.set("citations",e.path("citations"));
            if(p.responses){
                if(type.startsWith("response.web_search_call."))details.put("webSearchUsed",true);
                if(type.equals("response.output_item.done"))SearchMetadata.responses(e.path("item"),details);
                if(type.equals("response.output_text.delta")||type.equals("response.refusal.delta"))text(e.path("delta").asText());
                if(type.equals("response.reasoning_summary_text.delta")){String summary=e.path("delta").asText();summaryCharacters+=summary.length();if(summaryCharacters<=100000)sink.emit("reasoning",ModelGateway.JSON.createObjectNode().put("text",summary));}
                if(type.equals("response.output_text.annotation.added") && e.path("annotation").path("type").asText().equals("url_citation")) details.withArray("webCitations").add(e.path("annotation"));
                if(type.equals("response.output_text.delta")&&e.path("logprobs").isArray()&&(p.parameters.has("logprobs")||p.parameters.has("top_logprobs")))details.withArray("logprobs").addAll((ArrayNode)e.path("logprobs"));
                if(type.equals("response.completed")||type.equals("response.incomplete")){
                    JsonNode r=e.path("response");if(r.path("usage").isObject())usage.setAll((ObjectNode)r.path("usage"));for(JsonNode item:r.path("output"))SearchMetadata.responses(item,details);
                    if(characters==0)for(JsonNode item:r.path("output"))for(JsonNode part:item.path("content")){
                        if(part.path("type").asText().equals("output_text"))text(part.path("text").asText());
                        if(part.path("type").asText().equals("refusal"))text(part.path("refusal").asText());
                    }
                    if(type.equals("response.incomplete"))warning="模型返回了未完成的回复，已保留收到的文字。";
                    done=true;
                }
            }else if(p.provider.protocol.equals("anthropic")){
                if(type.equals("content_block_start")&&e.path("content_block").path("type").asText().equals("server_tool_use")&&e.path("content_block").path("name").asText().equals("web_search"))details.put("webSearchUsed",true);
                if(type.equals("content_block_delta")&&e.path("delta").path("type").asText().equals("citations_delta")){JsonNode citation=e.path("delta").path("citation");if(citation.path("type").asText().equals("web_search_result_location"))details.withArray("webCitations").addObject().put("url",citation.path("url").asText()).put("title",citation.path("title").asText()).put("end_index",-1);}
                if(type.equals("content_block_start")&&e.path("content_block").path("type").asText().equals("text"))text(e.path("content_block").path("text").asText());
                if(type.equals("content_block_delta")&&e.path("delta").path("type").asText().equals("text_delta"))text(e.path("delta").path("text").asText());
                if(type.equals("message_start")&&e.path("message").path("usage").isObject())usage.setAll((ObjectNode)e.path("message").path("usage"));
                if(type.equals("message_delta"))finish(e.path("delta").path("stop_reason").asText());
                if(type.equals("message_stop"))done=true;
            }else if(p.provider.protocol.equals("gemini")){
                JsonNode candidate=e.path("candidates").path(0);
                SearchMetadata.gemini(candidate.path("groundingMetadata"),details);
                for(JsonNode part:candidate.path("content").path("parts"))if(!part.path("thought").asBoolean())text(part.path("text").asText());
                finish(candidate.path("finishReason").asText());
                if(e.path("usageMetadata").isObject())usage.setAll((ObjectNode)e.path("usageMetadata"));
            }else{
                JsonNode choice=e.path("choices").path(0),content=choice.path("delta").path("content");
                if(content.isTextual())text(content.asText());else if(content.isArray())for(JsonNode part:content)text(part.path("text").asText());
                text(choice.path("delta").path("refusal").asText());finish(choice.path("finish_reason").asText(""));
                if(choice.path("logprobs").path("content").isArray()&&(p.parameters.has("logprobs")||p.parameters.has("top_logprobs")))details.withArray("logprobs").addAll((ArrayNode)choice.path("logprobs").path("content"));
            }
        }
    }
    private static ModelGateway.Problem bad(String text){return new ModelGateway.Problem(502,text);}
    static final class LimitedInput extends FilterInputStream {
        int remaining=8*1024*1024;
        LimitedInput(InputStream in){super(in);}
        public int read()throws IOException{if(remaining<=0)throw bad("流式响应过大。");int n=in.read();if(n>=0)remaining--;return n;}
        public int read(byte[] b,int off,int len)throws IOException{if(remaining<=0)throw bad("流式响应过大。");int n=in.read(b,off,Math.min(len,remaining));if(n>0)remaining-=n;return n;}
    }
    static void https(ModelGateway.Prepared p,ResponseHandler handler)throws IOException{
        ApiRoutes.requireOfficial(p.uri);ProviderOperations.beforeExternal();
        HttpsURLConnection c=(HttpsURLConnection)p.uri.toURL().openConnection();
        c.setRequestMethod("POST");c.setConnectTimeout(15000);c.setReadTimeout(90000);c.setInstanceFollowRedirects(false);
        p.headers.forEach(c::setRequestProperty);c.setRequestProperty("Accept","text/event-stream");
        byte[] body=ModelGateway.JSON.writeValueAsBytes(p.body);
        try{
            ProviderTransportPolicy.writeBody(c,body);
            int status=c.getResponseCode();ModelGateway.checkStatus(p.provider,status);
            try(InputStream in=c.getInputStream()){handler.accept(status,c.getContentType(),in);}
        }finally{c.disconnect();}
    }
}
