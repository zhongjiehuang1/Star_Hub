package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Native OpenAI media contracts; all destinations come from ApiRoutes. */
final class MediaGateway {
    interface Transport {Response call(String method,String url,String key,String type,byte[] body)throws IOException;}
    static final class Response {
        final int status;final String type;final byte[] bytes;final ObjectNode images;
        Response(int status,String type,byte[] bytes){this.status=status;this.type=type;this.bytes=bytes;this.images=null;}
        private Response(int status,String type,ObjectNode images){this.status=status;this.type=type;this.bytes=null;this.images=images;}
    }
    private final Transport transport;
    MediaGateway(){this(MediaGateway::https);}
    MediaGateway(Transport transport){this.transport=transport;}
    ObjectNode run(JsonNode r)throws IOException {
        JsonNode m=Catalog.find(r.path("provider").asText(),r.path("model").asText());
        String task=r.path("task").asText(),key=r.path("apiKey").asText().trim(),prompt=r.path("prompt").asText();
        if(!m.path("provider").asText().equals("openai")||m.path("batch").asBoolean()||!Catalog.contains(m.path("categories"),task))throw bad("请选择支持该任务的官方模型。");
        if(!key.matches("[\\x21-\\x7e]{1,4096}"))throw bad("请填写有效的 OpenAI API Key。");
        if(prompt.length()>30000||(!task.equals("transcription")&&prompt.isBlank()))throw bad("请输入任务内容，最多 30,000 字符。");
        ObjectNode params=Parameters.validate(r,m,task),body=ModelGateway.JSON.createObjectNode().put("model",m.path("id").asText());
        String type="application/json",url=ApiRoutes.media(m,task,r.path("jobId").asText());byte[] bytes;
        switch(task){
            case "image":
                if(!r.path("file").asText().isEmpty()||r.path("files").size()>0)throw bad("当前官方图像生成接入仅支持文字输入。");
                body.put("prompt",prompt);break;
            case "speech":
                String voice=r.path("voice").asText("coral");if(!Catalog.contains(m.path("voices"),voice))throw bad("请选择官方支持的声音。");
                body.put("input",prompt).put("voice",voice).put("response_format","mp3");break;
            case "embeddings":body.put("input",prompt).put("encoding_format","float");break;
            case "transcription":break;
            default:throw bad("此任务尚未接入官方 API。");
        }
        Parameters.apply(body,params,m,task);
        if(task.equals("transcription")){
            String file=validatedFile(r,false),format=r.path("fileFormat").asText();
            if(!Arrays.asList("mp3","wav","m4a","mp4","webm","ogg","flac").contains(format))throw bad("音频文件格式不受支持。");
            Multipart multipart=new Multipart();body.fields().forEachRemaining(e->multipart.field(e.getKey(),e.getValue().asText()));
            multipart.file("file","audio."+format,"application/octet-stream",Base64.getDecoder().decode(file.substring(file.indexOf(',')+1)));
            type=multipart.type();bytes=multipart.bytes();
        }else bytes=ModelGateway.JSON.writeValueAsBytes(body);
        Response response=transport.call("POST",url,key,type,bytes);
        if(response.status<200||response.status>=300)throw upstreamError(task,response);
        if(task.equals("image")){
            String mime="image/"+(params.path("output_format").asText("png").equals("jpeg")?"jpeg":params.path("output_format").asText("png"));
            return response.images!=null?response.images:StreamingMediaResponse.images(new ByteArrayInputStream(response.bytes),params.path("n").asInt(1),mime);
        }
        ObjectNode result=ModelGateway.JSON.createObjectNode();ArrayNode media=result.putArray("media");
        if(task.equals("speech")){
            String format=body.path("response_format").asText();
            if(format.equals("pcm")){media.addObject().put("type","file").put("filename","Star-Hub-audio.pcm").put("url","data:application/octet-stream;base64,"+Base64.getEncoder().encodeToString(response.bytes));}
            else {if(!response.type.startsWith("audio/")&&!response.type.startsWith("application/octet-stream"))throw badResponse();String mime=Map.of("mp3","audio/mpeg","opus","audio/ogg","aac","audio/aac","flac","audio/flac","wav","audio/wav").getOrDefault(format,"audio/mpeg");media.addObject().put("type","audio").put("url","data:"+mime+";base64,"+Base64.getEncoder().encodeToString(response.bytes));}
            return result.put("text","AI 合成语音已生成。");
        }
        if(task.equals("transcription")&&Arrays.asList("text","srt","vtt").contains(params.path("response_format").asText()))return result.put("text",new String(response.bytes,StandardCharsets.UTF_8));
        JsonNode value=ModelGateway.JSON.readTree(response.bytes);
        if(value==null||!value.isObject()||value.hasNonNull("error"))throw badResponse();
        if(task.equals("transcription"))result.put("text",value.path("text").asText()).set("details",value);
        else result.put("text",ModelGateway.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        return result;
    }
    private static ModelGateway.Problem badResponse(){return new ModelGateway.Problem(502,"官方 API 未返回有效结果。");}
    private static ModelGateway.Problem upstreamError(String task,Response response){
        if(task.equals("image")&&response.status==400){
            try {
                JsonNode error=ModelGateway.JSON.readTree(response.bytes);
                if(error!=null&&"moderation_blocked".equals(error.path("error").path("code").asText()))
                    return new ModelGateway.Problem(400,"图片请求被 OpenAI 内容审核拦截，请调整描述后重试。");
            } catch(IOException ignored) { }
        }
        return new ModelGateway.Problem(502,"官方 API 返回 HTTP "+response.status+"，请核对 Key、额度、权限及输入要求。");
    }
    static final class Multipart {
        private final String boundary="StarHub"+UUID.randomUUID().toString().replace("-","");
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();
        private void write(String s){out.writeBytes(s.getBytes(StandardCharsets.UTF_8));}
        void field(String name,String value){write("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n");}
        void file(String name,String filename,String mime,byte[] data){write("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"; filename=\""+filename+"\"\r\nContent-Type: "+mime+"\r\n\r\n");out.writeBytes(data);write("\r\n");}
        String type(){return "multipart/form-data; boundary="+boundary;}
        byte[] bytes(){write("--"+boundary+"--\r\n");return out.toByteArray();}
    }
    static String validatedFile(JsonNode r,boolean image){
        String file=r.path("file").asText();
        String regex=image?"data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+":"data:[a-zA-Z0-9.+/-]+;base64,[A-Za-z0-9+/=]+";
        if(file.length()>6*1024*1024||!file.matches(regex))throw bad(image?"请上传不超过 4 MB 的 PNG、JPEG 或 WebP 图片。":"请上传不超过 4 MB 的音频文件。");
        try{if(Base64.getDecoder().decode(file.substring(file.indexOf(',')+1)).length>4*1024*1024)throw bad("上传文件不能超过 4 MB。");}catch(IllegalArgumentException e){throw bad("文件编码无效。");}
        return file;
    }
    private static void addMedia(ArrayNode media,String type,String url){
        if(url.startsWith("https://")||url.startsWith("data:image/")||url.startsWith("data:audio/")||url.startsWith("data:video/"))media.addObject().put("type",type).put("url",url);
    }
    private static ModelGateway.Problem bad(String message){return new ModelGateway.Problem(400,message);}
    static Response https(String method,String url,String key,String type,byte[] body)throws IOException{
        ApiRoutes.requireOfficial(URI.create(url));ProviderOperations.beforeExternal();
        if(method.equals("GET")){var reply=ProviderTransportPolicy.get(URI.create(url),Map.of("Authorization","Bearer "+key,"Content-Type",type),90,16*1024*1024);return new Response(reply.status(),reply.contentType(),reply.bytes());}
        HttpsURLConnection c=(HttpsURLConnection)URI.create(url).toURL().openConnection();
        c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(90000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type",type);
        try{
            ProviderTransportPolicy.writeBody(c,body);
            int status=c.getResponseCode();if(status<200||status>=300){
                try(InputStream in=c.getErrorStream()){
                    return new Response(status,Objects.toString(c.getContentType(),""),in==null?new byte[0]:in.readNBytes(65536));
                }
            }
            try(InputStream in=c.getInputStream()){
                if(method.equals("POST")&&url.equals("https://api.openai.com/v1/images/generations")){
                    JsonNode request=ModelGateway.JSON.readTree(body);String format=request.path("output_format").asText("png"),mime="image/"+(format.equals("jpeg")?"jpeg":format);
                    return new Response(status,Objects.toString(c.getContentType(),"application/json"),StreamingMediaResponse.images(in,request.path("n").asInt(1),mime));
                }
                byte[] bytes=in.readNBytes(16*1024*1024+1);if(bytes.length>16*1024*1024)throw new ModelGateway.Problem(502,"媒体响应超过 16 MB，请在平台查看结果。");
                return new Response(status,Objects.toString(c.getContentType(),"application/json"),bytes);
            }
        }finally{c.disconnect();}
    }
}
