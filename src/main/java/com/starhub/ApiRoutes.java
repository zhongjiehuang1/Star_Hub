package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Manufacturer-owned endpoints only. Shared by the UI and request builders. */
final class ApiRoutes {
    private static final JsonNode LINKS;
    static {
        try(InputStream in=ApiRoutes.class.getResourceAsStream("/api-platforms.json")){
            LINKS=ModelGateway.JSON.readTree(Objects.requireNonNull(in));
        }catch(IOException e){throw new ExceptionInInitializerError(e);}
    }
    static boolean responses(ModelGateway.Provider p,String model){return p.id.equals("openai")&&model.matches("(gpt-[56].*|o[134].*)");}
    static String chat(ModelGateway.Provider p,String model,boolean stream){
        if(p.protocol.equals("gemini"))return p.base+"/models/"+URLEncoder.encode(model,StandardCharsets.UTF_8)+(stream?":streamGenerateContent?alt=sse":":generateContent");
        return p.base+(p.protocol.equals("anthropic")?"/messages":responses(p,model)?"/responses":"/chat/completions");
    }
    static String models(ModelGateway.Provider p){return p.base+"/models";}
    static String batch(){return ModelGateway.PROVIDERS.get("openai").base+"/batches";}
    static String files(){return ModelGateway.PROVIDERS.get("openai").base+"/files";}
    static String realtime(String model){return "wss://api.openai.com/v1/realtime?model="+URLEncoder.encode(model,StandardCharsets.UTF_8);}
    static String media(JsonNode m,String task,String job){
        if(!m.path("provider").asText().equals("openai")||!job.isEmpty())throw new ModelGateway.Problem(400,"该官方媒体接口不受支持。");
        String base=ModelGateway.PROVIDERS.get("openai").base;
        switch(task){
            case "image":return base+"/images/generations";
            case "speech":return base+"/audio/speech";
            case "transcription":return base+"/audio/transcriptions";
            case "embeddings":return base+"/embeddings";
            default:throw new ModelGateway.Problem(400,"接口类型不受支持。");
        }
    }
    static void requireOfficial(URI uri){
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new ModelGateway.Problem(400,"仅允许官方 API 地址。");
        for(ModelGateway.Provider p:ModelGateway.PROVIDERS.values()){
            URI base=URI.create(p.base);
            if(base.getHost().equals(uri.getHost())&&uri.getPath().startsWith(base.getPath()+"/"))return;
        }
        throw new ModelGateway.Problem(400,"仅允许官方 API 地址。");
    }
    static void enrich(JsonNode data){
        for(JsonNode provider:data.path("providers")){
            ObjectNode p=(ObjectNode)provider;String id=p.path("id").asText();
            if(LINKS.path(id).isObject())p.setAll((ObjectNode)LINKS.path(id));
            if(ModelGateway.PROVIDERS.containsKey(id)){p.put("baseUrl",ModelGateway.PROVIDERS.get(id).base);if(!id.equals("qwen"))p.put("checkUrl",models(ModelGateway.PROVIDERS.get(id)));}
        }
        for(JsonNode entry:data.path("models")){
            ObjectNode m=(ObjectNode)entry;String provider=m.path("provider").asText(),id=m.path("id").asText();
            ObjectNode routes=m.putObject("apiRoutes");
            for(JsonNode kind:m.path("categories")){
                String task=kind.asText();if(task.equals("vision"))continue;
                ArrayNode list=routes.putArray(task);
                if(m.path("batch").asBoolean()){
                    add(list,"上传批处理文件","POST",files(),false);add(list,"提交批处理","POST",batch(),false);
                    add(list,"读取批次","GET",batch()+"/{jobId}",true);add(list,"读取结果文件","GET",files()+"/{fileId}/content",true);add(list,"列出批次","GET",batch()+"?limit=20",false);
                }else if(task.equals("realtime"))add(list,"实时语音连接","WebSocket",realtime(id),false);
                else if(task.equals("chat"))add(list,"流式文字对话","POST",chat(ModelGateway.PROVIDERS.get(provider),id,true),false);
                else add(list,"生成 / 处理","POST",media(m,task,""),false);
            }
        }
    }
    private static void add(ArrayNode list,String label,String method,String url,boolean template){list.addObject().put("label",label).put("method",method).put("url",url).put("template",template);}
}
