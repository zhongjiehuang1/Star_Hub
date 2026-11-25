package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.util.*;

/** One catalog contract drives the form and all server-side parameter validation. */
final class Parameters {
    static ObjectNode validate(JsonNode request,JsonNode model,String task){
        JsonNode input=request.path("parameters");
        if(!input.isMissingNode()&&!input.isObject())throw bad("模型设置必须为 JSON 对象。");
        if(input.size()>64)throw bad("模型设置过多。");
        int settingsLength=0;
        Map<String,JsonNode> definitions=new LinkedHashMap<>();
        for(JsonNode f:model.path("settings").path(task))definitions.put(f.path("key").asText(),f);
        ObjectNode values=ModelGateway.JSON.createObjectNode();
        Iterator<String> keys=input.fieldNames();
        while(keys.hasNext()){
            String key=keys.next();JsonNode spec=definitions.get(key),value=input.path(key);
            if(spec==null)throw bad("当前模型不支持设置："+safeKey(key));
            String label=spec.path("label").asText(key),type=spec.path("type").asText();
            if(!type.equals("image")){settingsLength+=value.toString().length();if(settingsLength>40000)throw bad("模型设置过长。");}
            if(type.equals("enum")){
                boolean found=false;for(JsonNode allowed:spec.path("values"))if(allowed.equals(value)){found=true;break;}
                if(!found)throw bad(label+"的选项无效。");
            }else if(type.equals("number")||type.equals("integer")){
                if(!value.isNumber()||!Double.isFinite(value.asDouble())||(type.equals("integer")&&!value.isIntegralNumber())||value.asDouble()<spec.path("min").asDouble()||value.asDouble()>spec.path("max").asDouble())throw bad(label+"超出允许范围或类型不正确。");
            }else if(type.equals("boolean")){
                if(!value.isBoolean())throw bad(label+"必须为开关值。");
            }else if(type.equals("lines")){
                if(!value.isArray()||value.isEmpty()||value.size()>spec.path("maxItems").asInt(10))throw bad(label+"的条目数量无效。");
                for(JsonNode line:value)checkString(line,spec,label);
            }else if(type.equals("json")){
                if(!value.isObject()||value.toString().length()>spec.path("maxLength").asInt(16000))throw bad(label+"需要有效的 JSON 对象。");
            }else if(type.equals("image")){
                if(!value.isTextual())throw bad(label+"需要图片。");
                MediaGateway.validatedFile(ModelGateway.JSON.createObjectNode().put("file",value.asText()),true);
            }else{
                checkString(value,spec,label);
                if(type.equals("url"))checkUrl(value.asText());
            }
            JsonNode restriction=spec.path("restrictedValues").path(value.asText());
            if(!restriction.isMissingNode()&&!enabled(ModelGateway.JSON.createObjectNode().set("when",restriction),values,request.path("effort").asText("default"),model))throw bad(label+"的选项不适用于当前推理档位。");
            values.set(key,value);
        }
        for(JsonNode spec:definitions.values()){
            String key=spec.path("key").asText();boolean enabled=enabled(spec,values,request.path("effort").asText("default"),model);
            if(values.has(key)&&!enabled)throw bad(spec.path("label").asText()+"不适用于当前设置组合。");
            if(enabled&&spec.path("required").asBoolean()&&!values.has(key))throw bad("请填写"+spec.path("label").asText()+"。");
        }
        if(values.has("json_schema")){
            JsonNode schema=values.path("json_schema");if(!schema.has("type")&&!schema.has("anyOf"))throw bad("JSON Schema 需要 type 或 anyOf。");
        }
        if(values.has("logit_bias")){
            if(values.path("logit_bias").size()>100)throw bad("最多设置 100 个 token 偏置。");
            Iterator<Map.Entry<String,JsonNode>> it=values.path("logit_bias").fields();
            while(it.hasNext()){Map.Entry<String,JsonNode> e=it.next();if(!e.getKey().matches("[0-9]{1,10}")||!e.getValue().isNumber()||Math.abs(e.getValue().asDouble())>100)throw bad("Token 偏置需要 token ID 和 -100～100 的数值。");}
        }
        if(values.has("thinking_budget")&&model.path("provider").asText().equals("anthropic")&&values.path("thinking_budget").asInt()>=values.path("max_tokens").asInt(4096))throw bad("思考预算必须小于最大输出 token；未设置输出上限时默认为 4096。");
        if(values.path("background").asText().equals("transparent")&&values.path("output_format").asText().equals("jpeg"))throw bad("透明背景不支持 JPEG，请选择 PNG 或 WebP。");
        if(task.equals("rerank")&&values.path("top_n").asInt() > request.path("documents").size())throw bad("返回文档数量不能超过输入文档数量。");
        if(values.has("size")&&(values.has("resolution")||values.has("aspect_ratio")))throw bad("精确像素尺寸与分辨率 / 比例请选择一种设置方式。");
        return values;
    }
    static boolean enabled(JsonNode spec,JsonNode values,String effort,JsonNode model){
        Iterator<Map.Entry<String,JsonNode>> it=spec.path("when").fields();
        while(it.hasNext()){
            Map.Entry<String,JsonNode> e=it.next();
            JsonNode value=e.getKey().equals("$effort")?new TextNode(effort.equals("default")?model.path("reasoningDefault").asText(""):effort):values.path(e.getKey());
            boolean match=false;for(JsonNode expected:e.getValue())if(expected.asText().equals("$set")?!value.isMissingNode()&&!value.isNull()&&!value.asText().isEmpty():expected.equals(value)){match=true;break;}
            if(!match)return false;
        }return true;
    }
    static void apply(ObjectNode body,JsonNode values,JsonNode model,String task){
        for(JsonNode spec:model.path("settings").path(task)){
            String key=spec.path("key").asText();if(!values.has(key))continue;
            if(Arrays.asList("last_frame","source_video","source_audio").contains(key))continue;
            JsonNode value=values.path(key);
            if(key.equals("timestamp_granularities")){ArrayNode a=ModelGateway.JSON.createArrayNode();for(String v:value.asText().split(","))a.add(v);value=a;}
            if(spec.path("fields").isArray()){for(JsonNode path:spec.path("fields"))put(body,path.asText(),value);}else put(body,spec.path("field").asText(key),value);
        }
    }
    static void chat(ObjectNode body,JsonNode values,JsonNode model,ModelGateway.Provider provider,boolean responses){
        boolean gemini=provider.protocol.equals("gemini"),claude=provider.protocol.equals("anthropic");
        for(JsonNode spec:model.path("settings").path("chat")){
            String key=spec.path("key").asText();if(!values.has(key))continue;JsonNode value=values.path(key);String path=spec.path("field").asText(key);
            if(Arrays.asList("json_schema","response_format","image_detail","thinking","thinking_budget").contains(key))continue;
            if(key.equals("max_tokens"))path=gemini?"generationConfig.maxOutputTokens":claude?"max_tokens":responses?"max_output_tokens":path;
            if(key.equals("stop"))path=gemini?"generationConfig.stopSequences":claude?"stop_sequences":"stop";
            if(gemini&&Arrays.asList("temperature","top_p","top_k","seed").contains(key))path="generationConfig."+Map.of("temperature","temperature","top_p","topP","top_k","topK","seed","seed").get(key);
            if(key.equals("verbosity")&&responses)path="text.verbosity";
            if(key.equals("prediction")){put(body,"prediction.type",new TextNode("content"));path="prediction.content";}
            put(body,path,value);
        }
        if(values.has("response_format")){
            String format=values.path("response_format").asText();ObjectNode spec=ModelGateway.JSON.createObjectNode().put("type",format);
            if(format.equals("json_schema")){
                if(claude||gemini)spec.set("schema",values.path("json_schema"));
                else if(responses){spec.put("name","starhub_result").put("strict",true).set("schema",values.path("json_schema"));}
                else spec.putObject("json_schema").put("name","starhub_result").put("strict",true).set("schema",values.path("json_schema"));
            }
            if(gemini){put(body,"generationConfig.responseMimeType",new TextNode("application/json"));if(values.has("json_schema"))put(body,"generationConfig.responseJsonSchema",values.path("json_schema"));}
            else put(body,claude?"output_config.format":responses?"text.format":"response_format",spec);
        }
        if(values.has("thinking"))body.putObject("thinking").put("type",values.path("thinking").asText());
        if(values.has("thinking_budget"))put(body,gemini?"generationConfig.thinkingConfig.thinkingBudget":"thinking.budget_tokens",values.path("thinking_budget"));
        if(responses&&values.has("top_logprobs"))body.putArray("include").add("message.output_text.logprobs");
    }
    static void put(ObjectNode target,String path,JsonNode value){
        String[] keys=path.split("\\.");ObjectNode cursor=target;
        for(int i=0;i<keys.length-1;i++){
            JsonNode existing=cursor.get(keys[i]);if(existing==null||!existing.isObject())existing=cursor.putObject(keys[i]);cursor=(ObjectNode)existing;
        }cursor.set(keys[keys.length-1],value.deepCopy());
    }
    static void checkUrl(String value){
        try{URI u=URI.create(value);String host=u.getHost();if(!"https".equals(u.getScheme())||host==null||u.getUserInfo()!=null||host.equals("localhost")||host.endsWith(".local")||host.startsWith("127.")||host.startsWith("10.")||host.startsWith("192.168.")||host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*")||host.startsWith("169.254.")||host.startsWith("["))throw new IllegalArgumentException();}catch(Exception e){throw bad("参考地址必须是公开 HTTPS 地址，且不能含登录凭据。");}
    }
    private static void checkString(JsonNode value,JsonNode spec,String label){
        if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>spec.path("maxLength").asInt(4000)||spec.has("pattern")&&!value.asText().matches(spec.path("pattern").asText()))throw bad(label+"的格式或长度不正确。");
    }
    private static String safeKey(String key){return key.matches("[a-zA-Z0-9_]{1,50}")?key:"未知参数";}
    private static ModelGateway.Problem bad(String text){return new ModelGateway.Problem(400,text);}
}
