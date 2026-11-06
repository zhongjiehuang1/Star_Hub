package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** A per-connection, in-memory relay. No URL ever contains credentials. */
final class RealtimeBridge {
    interface Socket { CompletionStage<Void> send(String text); void close(); }
    interface Downstream { CompletionStage<Void> send(String text); void close(); }
    interface Connector { CompletableFuture<Socket> open(String model,String key,Consumer<String> receive,Runnable closed,Consumer<Throwable> failed); }
    private final Downstream downstream;
    private final Connector connector;
    private Socket upstream;
    private CompletableFuture<Void> upChain=CompletableFuture.completedFuture(null), downChain=CompletableFuture.completedFuture(null);
    private int upBytes,downBytes;
    private boolean requested,ready,closed,transcription;
    private long started=System.currentTimeMillis(),lastActivity=started;
    RealtimeBridge(Downstream downstream,Connector connector){this.downstream=downstream;this.connector=connector;}
    synchronized boolean expired(){long now=System.currentTimeMillis();return now-started>30*60*1000L||now-lastActivity>120000||(!ready&&now-started>20000);}
    synchronized void receive(String text){
        if(closed)return;lastActivity=System.currentTimeMillis();
        try{
            if(text.length()>6*1024*1024)throw new IllegalArgumentException();
            JsonNode e=ModelGateway.JSON.readTree(text);String type=e.path("type").asText();
            if(type.equals("starhub.connect")){
                if(requested)throw new IllegalArgumentException();requested=true;
                String provider=e.path("provider").asText(),model=e.path("model").asText(),key=e.path("apiKey").asText().trim();
                JsonNode item=Catalog.find(provider,model);
                if(!provider.equals("openai")||!Catalog.contains(item.path("categories"),"realtime")||!key.matches("[\\x21-\\x7e]{1,4096}"))throw new IllegalArgumentException();
                transcription=!Catalog.contains(item.path("outputs"),"audio");
                ObjectNode config=sessionConfig(item,e.path("voice").asText());
                Parameters.apply((ObjectNode)config.path("session"),Parameters.validate(e,item,"realtime"),item,"realtime");
                connector.open(model,key,this::receiveUpstream,()->fail("实时连接已结束。"),error->fail("无法连接实时平台，请检查 Key、余额、模型权限和网络。")).whenComplete((socket,error)->{
                    synchronized(this){
                        if(error!=null){fail("实时连接失败，请检查 Key、余额和模型权限。");return;}
                        if(closed){socket.close();return;}upstream=socket;sendUp(config.toString());
                    }
                });
                return;
            }
            if(!ready||upstream==null)throw new IllegalArgumentException();
            if(type.equals("starhub.ping")){sendDown("{\"type\":\"starhub.pong\"}");return;}
            validateEvent(e,transcription);sendUp(text);
        }catch(Exception ignored){fail("实时请求格式无效，或模型 / Key 不受支持。");}
    }
    static ObjectNode sessionConfig(JsonNode item,String voice){
        boolean transcript=!Catalog.contains(item.path("outputs"),"audio");
        if(!voice.isBlank()&&!Catalog.contains(item.path("voices"),voice))throw new IllegalArgumentException();
        ObjectNode event=ModelGateway.JSON.createObjectNode().put("type","session.update"),session=event.putObject("session").put("type",transcript?"transcription":"realtime");
        ObjectNode audio=session.putObject("audio"),input=audio.putObject("input");
        input.putObject("format").put("type","audio/pcm").put("rate",24000);input.putNull("turn_detection");input.putNull("noise_reduction");
        if(transcript)input.putObject("transcription").put("model",item.path("id").asText());
        else{
            session.putArray("output_modalities").add("audio");
            ObjectNode output=audio.putObject("output");output.putObject("format").put("type","audio/pcm").put("rate",24000);
            if(!voice.isBlank())output.put("voice",voice);
        }
        return event;
    }
    static void validateEvent(JsonNode event,boolean transcription){
        String type=event.path("type").asText();
        if(type.equals("input_audio_buffer.append")){
            String audio=event.path("audio").asText();
            if(audio.length()>128000||audio.isEmpty()||!audio.matches("[A-Za-z0-9+/=]+"))throw new IllegalArgumentException();
            byte[] bytes=Base64.getDecoder().decode(audio);if(bytes.length%2!=0)throw new IllegalArgumentException();return;
        }
        if(type.equals("input_audio_buffer.commit"))return;
        if(transcription)throw new IllegalArgumentException();
        if(type.equals("response.create")){
            if(event.has("response"))throw new IllegalArgumentException();return;
        }
        if(type.equals("response.cancel"))return;
        if(type.equals("conversation.item.truncate")){
            if(!event.path("item_id").asText().matches("[A-Za-z0-9_-]{1,150}")||event.path("audio_end_ms").asInt(-1)<0||event.path("content_index").asInt(-1)<0)throw new IllegalArgumentException();return;
        }
        if(type.equals("conversation.item.create")){
            JsonNode item=event.path("item");if(!item.path("role").asText().equals("user")||!item.path("type").asText().equals("message"))throw new IllegalArgumentException();
            JsonNode parts=item.path("content");if(!parts.isArray()||parts.size()<1||parts.size()>2)throw new IllegalArgumentException();
            for(JsonNode part:parts){
                if(part.path("type").asText().equals("input_text")){if(part.path("text").asText().isBlank()||part.path("text").asText().length()>30000)throw new IllegalArgumentException();}
                else if(part.path("type").asText().equals("input_image")){String url=part.path("image_url").asText();if(url.length()>6*1024*1024||!url.matches("data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+"))throw new IllegalArgumentException();}
                else throw new IllegalArgumentException();
            }return;
        }
        throw new IllegalArgumentException();
    }
    synchronized void receiveUpstream(String text){
        if(closed)return;lastActivity=System.currentTimeMillis();
        try{
            JsonNode event=ModelGateway.JSON.readTree(text);String type=event.path("type").asText();
            if(type.equals("error")){String code=event.path("error").path("code").asText();if(!code.matches("[a-zA-Z0-9_-]{1,80}"))code="provider_error";sendDown(ModelGateway.JSON.createObjectNode().put("type","starhub.error").put("message","实时平台拒绝本次操作（"+code+"）。请检查输入或账户权限。").toString());return;}
            if(type.equals("session.updated")){ready=true;sendDown("{\"type\":\"starhub.ready\"}");return;}
            if(type.equals("session.created"))return;
            // Forward only data needed by the workbench, never raw errors or session settings.
            ObjectNode out=ModelGateway.JSON.createObjectNode().put("type",type);
            for(String field:new String[]{"item_id","response_id","content_index","output_index","delta","text","transcript"})if(event.has(field))out.set(field,event.path(field));
            if(type.equals("response.done")||type.equals("response.created")){out.putObject("response").put("status",event.path("response").path("status").asText()).put("id",event.path("response").path("id").asText());}
            sendDown(out.toString());
        }catch(Exception ignored){fail("实时平台返回了无法识别的数据。");}
    }
    private synchronized void sendUp(String text){
        if(closed)return;upBytes+=text.length();if(upBytes>8*1024*1024){fail("发送队列已满，连接已停止，请检查网络。");return;}
        Socket socket=upstream;upChain=upChain.thenCompose(v->socket.send(text).toCompletableFuture());
        upChain.whenComplete((v,e)->{synchronized(this){upBytes-=text.length();}if(e!=null)fail("实时发送失败，连接已结束。");});
    }
    private synchronized CompletionStage<Void> sendDown(String text){
        if(closed)return CompletableFuture.completedFuture(null);
        downBytes+=text.length();if(downBytes>2*1024*1024){close();return CompletableFuture.failedFuture(new IOException());}
        downChain=downChain.thenCompose(v->downstream.send(text).toCompletableFuture());
        downChain.whenComplete((v,e)->{synchronized(this){downBytes-=text.length();}if(e!=null)close();});return downChain;
    }
    synchronized void fail(String message){if(closed)return;sendDown(ModelGateway.JSON.createObjectNode().put("type","starhub.error").put("message",message).toString()).whenComplete((v,e)->close());}
    synchronized void close(){if(closed)return;closed=true;if(upstream!=null)upstream.close();downstream.close();}
    static Connector network(HttpClient client){
        return (model,key,receive,closed,failed)->{
            URI uri=URI.create(ApiRoutes.realtime(model));
            WebSocket.Listener listener=new WebSocket.Listener(){
                final StringBuilder fragment=new StringBuilder();
                public void onOpen(WebSocket socket){socket.request(1);}
                public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){
                    if(fragment.length()+text.length()>1024*1024){socket.abort();failed.accept(new IOException());return CompletableFuture.completedFuture(null);}
                    fragment.append(text);if(last){receive.accept(fragment.toString());fragment.setLength(0);}socket.request(1);return CompletableFuture.completedFuture(null);
                }
                public CompletionStage<?> onClose(WebSocket socket,int status,String reason){closed.run();return CompletableFuture.completedFuture(null);}
                public void onError(WebSocket socket,Throwable error){failed.accept(error);}
            };
            return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+key).buildAsync(uri,listener).thenApply(ws->new Socket(){
                public CompletionStage<Void> send(String text){return ws.sendText(text,true).thenApply(s->null);}
                public void close(){ws.sendClose(1000,"Session ended").orTimeout(2,TimeUnit.SECONDS).whenComplete((v,e)->ws.abort());}
            });
        };
    }
}
