package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** OpenAI native Files + Batch API; no third-party batch service. */
final class BatchGateway {
    private final MediaGateway.Transport transport;
    BatchGateway(){this(MediaGateway::https);}
    BatchGateway(MediaGateway.Transport transport){this.transport=transport;}
    ObjectNode run(JsonNode r)throws IOException{
        if(!r.path("provider").asText().equals("openai"))throw bad("批处理仅支持 OpenAI 官方 API。");
        String key=r.path("apiKey").asText().trim();if(!key.matches("[\\x21-\\x7e]{1,4096}"))throw bad("请填写 OpenAI API Key。");
        String action=r.path("action").asText("submit");ObjectNode result=ModelGateway.JSON.createObjectNode();
        JsonNode value;
        if(action.equals("submit")){
            JsonNode m=Catalog.find("openai",r.path("model").asText());if(!m.path("batch").asBoolean())throw bad("请选择官方批处理接入项。");
            String id=m.path("apiModel").asText();JsonNode inputs=r.path("inputs");
            if(!inputs.isArray()||inputs.size()<1||inputs.size()>100)throw bad("每批需要 1–100 条任务。");
            boolean embeddings=Catalog.contains(m.path("categories"),"embeddings");String endpoint=embeddings?"/v1/embeddings":"/v1/chat/completions";
            ObjectNode params=Parameters.validate(r,m,embeddings?"embeddings":"chat");String effort=Catalog.effort(r,m);StringBuilder jsonl=new StringBuilder();int total=0;
            for(int i=0;i<inputs.size();i++){
                String input=inputs.get(i).asText("");total+=input.length();
                if(!inputs.get(i).isTextual()||input.isBlank()||input.length()>30000||total>100000)throw bad("每条最多 30,000 字符，一批累计最多 100,000 字符。");
                ObjectNode line=ModelGateway.JSON.createObjectNode().put("custom_id",String.format("task-%03d",i+1)).put("method","POST").put("url",endpoint);
                ObjectNode body=line.putObject("body").put("model",id);
                if(embeddings){body.put("input",input).put("encoding_format","float");Parameters.apply(body,params,m,"embeddings");}
                else{if(params.has("response_format"))input="请以 JSON 格式回答。\n"+input;body.putArray("messages").addObject().put("role","user").put("content",input);if(!effort.isEmpty())body.put("reasoning_effort",effort);Parameters.chat(body,params,m,ModelGateway.PROVIDERS.get("openai"),false);}
                jsonl.append(ModelGateway.JSON.writeValueAsString(line)).append('\n');
            }
            MediaGateway.Multipart file=new MediaGateway.Multipart();file.field("purpose","batch");file.file("file","starhub-batch.jsonl","application/jsonl",jsonl.toString().getBytes(StandardCharsets.UTF_8));
            JsonNode upload=read(call("POST",ApiRoutes.files(),key,file.type(),file.bytes()));String fileId=upload.path("id").asText();validId(fileId);
            ObjectNode body=ModelGateway.JSON.createObjectNode().put("input_file_id",fileId).put("endpoint",endpoint).put("completion_window","24h");
            body.putObject("metadata").put("app","Star Hub").put("model",id);
            value=read(call("POST",ApiRoutes.batch(),key,"application/json",ModelGateway.JSON.writeValueAsBytes(body)));
        }else if(action.equals("get")){
            String id=r.path("jobId").asText();validId(id);value=read(call("GET",ApiRoutes.batch()+"/"+id,key,"application/json",new byte[0]));
            ArrayNode results=((ObjectNode)value).putArray("results");
            for(String kind:new String[]{"output_file_id","error_file_id"})if(value.hasNonNull(kind)){
                String fileId=value.path(kind).asText();validId(fileId);
                String jsonl=new String(call("GET",ApiRoutes.files()+"/"+fileId+"/content",key,"application/json",new byte[0]).bytes,StandardCharsets.UTF_8);
                for(String line:jsonl.split("\n"))if(!line.isBlank()){if(results.size()>=1000)throw new ModelGateway.Problem(502,"结果超过本站查看上限，请在 OpenAI 控制台下载。");JsonNode row=ModelGateway.JSON.readTree(line);if(row==null||!row.isObject())throw new ModelGateway.Problem(502,"官方批处理结果格式无效。");results.add(row);}
            }
        }else if(action.equals("list")){
            String url=ApiRoutes.batch()+"?limit=20",after=r.path("after").asText();if(!after.isEmpty()){validId(after);url+="&after="+after;}
            value=read(call("GET",url,key,"application/json",new byte[0]));
            ArrayNode jobs=result.putArray("jobs");for(JsonNode item:value.path("data"))jobs.add(normalize(item));
            return result.put("hasMore",value.path("has_more").asBoolean()).put("after",value.path("last_id").asText());
        }else throw bad("不支持此批处理操作。");
        result.set("job",normalize(value));return result;
    }
    private MediaGateway.Response call(String method,String url,String key,String type,byte[] bytes)throws IOException{
        MediaGateway.Response response=transport.call(method,url,key,type,bytes);
        if(response.status<200||response.status>=300)throw new ModelGateway.Problem(502,"OpenAI 官方批处理返回 HTTP "+response.status+"。请核对 Key、额度及任务 ID；提交结果不明时先查询任务，避免重复计费。");return response;
    }
    private static JsonNode read(MediaGateway.Response response)throws IOException{
        JsonNode value=ModelGateway.JSON.readTree(response.bytes);if(value==null||!value.isObject()||value.hasNonNull("error"))throw new ModelGateway.Problem(502,"官方批处理响应无效。");return value;
    }
    private static void validId(String id){if(!id.matches("[A-Za-z0-9_-]{1,180}"))throw bad("任务或文件 ID 无效。");}
    static ObjectNode normalize(JsonNode source){
        if(!source.path("id").isTextual()||!source.has("status"))throw new ModelGateway.Problem(502,"平台未返回批处理任务。");
        ObjectNode job=ModelGateway.JSON.createObjectNode();
        for(String key:new String[]{"id","model","status","created_at","expires_at","finalized_at"})if(source.hasNonNull(key))job.set(key,source.path(key));
        if(source.path("metadata").has("model"))job.set("model",source.path("metadata").path("model"));
        ObjectNode counts=job.putObject("counts");for(String key:new String[]{"total","completed","failed"})counts.put(key,source.path("request_counts").path(key).asInt());
        ArrayNode results=job.putArray("results");
        for(JsonNode row:source.path("results")){
            ObjectNode out=results.addObject().put("id",row.path("custom_id").asText());
            JsonNode response=row.path("response"),body=response.path("body");int status=response.path("status_code").asInt(200);
            if(row.hasNonNull("error")||body.hasNonNull("error")||status>=400){out.put("error","此条任务失败，请检查输入或平台权限。");continue;}
            StringBuilder text=new StringBuilder();JsonNode content=body.path("choices").path(0).path("message").path("content");
            if(content.isTextual())text.append(content.asText());else for(JsonNode part:content)text.append(part.path("text").asText(""));
            for(JsonNode item:body.path("output"))for(JsonNode part:item.path("content"))if(part.path("type").asText().equals("output_text"))text.append(part.path("text").asText(""));
            for(JsonNode part:body.path("content"))if(part.path("type").asText().equals("text"))text.append(part.path("text").asText(""));
            out.put("text",text.toString());
            if(body.path("data").isArray()){
                ArrayNode vectors=out.putArray("vectors");for(JsonNode vector:body.path("data"))if(vector.path("embedding").isArray()||vector.path("embedding").isTextual())vectors.add(vector.path("embedding"));
            }
            if(body.path("usage").isObject())out.set("usage",body.path("usage"));
        }
        if(source.hasNonNull("error"))job.put("error","平台报告此批次失败，请检查账户、模型权限与任务内容。");
        return job;
    }
    private static ModelGateway.Problem bad(String text){return new ModelGateway.Problem(400,text);}
}
