package com.starhub;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

final class ModelGateway {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, Provider> PROVIDERS = providers();
    interface Transport { Reply send(URI uri, Map<String, String> headers, byte[] body) throws IOException; }
    static final class Reply {
        final int status; final byte[] body;
        Reply(int status, byte[] body) { this.status = status; this.body = body; }
    }
    static final class Problem extends RuntimeException {
        final int status;
        Problem(int status, String message) { super(message); this.status = status; }
    }
    static final class Provider {
        final String id, name, protocol, base, example;
        final List<String> models;
        Provider(String id, String name, String protocol, String base, String... models) {
            this.id = id; this.name = name; this.protocol = protocol; this.base = base;
            this.models = Arrays.asList(models);
            this.example = models.length == 0 ? "" : models[0];
        }
    }
    private final Transport transport;
    ModelGateway() { this(ModelGateway::https); }
    ModelGateway(Transport transport) { this.transport = transport; }

    private static Map<String, Provider> providers() {
        Map<String, Provider> all = new LinkedHashMap<>();
        add(all, "openai", "OpenAI", "openai", "https://api.openai.com/v1", "gpt-4.1-mini", "gpt-4.1");
        add(all, "anthropic", "Claude", "anthropic", "https://api.anthropic.com/v1", "claude-sonnet-4-5", "claude-haiku-4-5");
        add(all, "gemini", "Gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash");
        add(all, "deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1", "deepseek-flash", "deepseek-v4-pro");
        add(all, "qwen", "通义千问", "openai", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "qwen-flash", "qwen-max");
        add(all, "moonshot", "Kimi", "openai", "https://api.moonshot.cn/v1", "kimi-k2.5");
        add(all, "xai", "xAI", "openai", "https://api.x.ai/v1", "grok-4");
        return Collections.unmodifiableMap(all);
    }
    private static void add(Map<String, Provider> all, String id, String name, String protocol, String base, String... models) {
        all.put(id, new Provider(id, name, protocol, base, models));
    }
    static ArrayNode catalogue() {
        ArrayNode out = JSON.createArrayNode();
        PROVIDERS.values().forEach(p -> {
            ObjectNode item = out.addObject().put("id", p.id).put("name", p.name).put("protocol", p.protocol).put("example", p.example).put("baseUrl", p.base);
            ArrayNode models = item.putArray("models");
            p.models.forEach(models::add);
        });
        return out;
    }
    static Prepared prepare(JsonNode request, boolean stream) throws IOException {
        if (request == null || !request.isObject()) throw new Problem(400, "请求必须是 JSON 对象。");
        Provider p = PROVIDERS.get(request.path("provider").asText());
        if (p == null) throw new Problem(400, "请选择支持的服务商。");
        String key = request.path("apiKey").asText("").trim();
        if (key.isEmpty() || key.length() > 4096 || !key.matches("[\\x21-\\x7e]+")) throw new Problem(400, "请填写有效的 API Key。");
        String model = request.path("model").asText("").trim();
        if (!model.matches("[~A-Za-z0-9][A-Za-z0-9._:/@+~-]{0,199}")) throw new Problem(400, "请填写有效的模型 ID。");
        String effort = request.path("effort").asText("default");
        JsonNode item = null;
        if (!effort.equals("default") && !effort.isBlank()) { item = Catalog.find(p.id, model); effort = Catalog.effort(request, item); } else effort = "";
        JsonNode modelInfo = Catalog.find(p.id, model);
        if(modelInfo.path("execution").asText().equals("external"))throw new Problem(400,modelInfo.path("executionNote").asText("该模型不支持当前工作台的调用方式，请查看模型接入说明。"));
        if(modelInfo.path("batch").asBoolean() || !Catalog.contains(modelInfo.path("categories"),"chat"))throw new Problem(400,"请选择普通对话模型或使用对应任务面板。");
        ObjectNode parameters=Parameters.validate(request,modelInfo,"chat");
        JsonNode messages = request.path("messages");
        if (!messages.isArray() || messages.size() < 1 || messages.size() > 60) throw new Problem(400, "每次请求需要 1–60 条消息，请新建对话后重试。");
        int total = 0;
        for (JsonNode msg : messages) {
            String role = msg.path("role").asText();
            JsonNode content = msg.path("content");
            if (!(role.equals("user") || role.equals("assistant")) || !content.isTextual() || content.asText().isBlank())
                throw new Problem(400, "消息必须包含 user / assistant 角色和非空文本。");
            total += content.asText().length();
        }
        String system = request.path("system").asText("").trim();
        String zoneName = request.path("timeZone").asText("UTC");
        java.time.ZoneId zone;
        try { zone = java.time.ZoneId.of(zoneName); } catch (java.time.DateTimeException ex) { zone = java.time.ZoneOffset.UTC; }
        String clock = java.time.ZonedDateTime.now(zone).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        system += "\nCurrent date and time at request: " + clock + " (time zone: " + zone.getId() + "). This is the request time, not a source of current events. Do not claim to have searched unless a search was actually performed.";
        if(parameters.has("response_format"))system += "\n请按所指定的 JSON 输出格式回答。";
        if (total + system.length() > 100000 || system.length() > 8000) throw new Problem(400, "对话过长，请缩短内容或新建对话。");
        if (!messages.get(0).path("role").asText().equals("user") || !messages.get(messages.size() - 1).path("role").asText().equals("user"))
            throw new Problem(400, "对话必须以用户消息开始和结束。");
        ObjectNode body = JSON.createObjectNode();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (p.protocol.equals("gemini")) {

            headers.put("x-goog-api-key", key);
            ArrayNode contents = body.putArray("contents");
            for (JsonNode msg : messages) {
                ObjectNode entry = contents.addObject().put("role", msg.path("role").asText().equals("assistant") ? "model" : "user");
                entry.putArray("parts").addObject().put("text", msg.path("content").asText());
            }
            if (!system.isEmpty()) body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        } else {
            body.put("model", model);
            ArrayNode list = body.putArray("messages");
            if (p.protocol.equals("anthropic")) {

                headers.put("x-api-key", key);
                headers.put("anthropic-version", "2023-06-01");
                body.put("max_tokens", 4096);
                if (!system.isEmpty()) body.put("system", system);
            } else {

                headers.put("Authorization", "Bearer " + key);
                if (!system.isEmpty()) list.addObject().put("role", "system").put("content", system);
            }
            for (JsonNode msg : messages) list.addObject().put("role", msg.path("role").asText()).put("content", msg.path("content").asText());
        }
        if (!effort.isEmpty()) {
            String mode = item.path("reasoningMode").asText();
            if (mode.equals("openai")) body.put("reasoning_effort", effort);
            else if (mode.equals("anthropic")) body.putObject("output_config").put("effort", effort);
            else if (mode.equals("gemini")) body.putObject("generationConfig").putObject("thinkingConfig").put("thinkingLevel", effort);
        }
        for(int index=0;index<messages.size();index++) {
            JsonNode message=messages.get(index);java.util.List<String> images=new java.util.ArrayList<>();
            if(!message.path("image").asText("").isBlank())images.add(message.path("image").asText());
            if(message.path("images").isArray())for(JsonNode image:message.path("images"))images.add(image.asText());
            if(images.isEmpty())continue;
            if(images.size()>4||!Catalog.contains(modelInfo.path("inputs"),"image")||!message.path("role").asText().equals("user"))throw new Problem(400,"当前模型或消息不支持图片输入（最多四张）。");
            ArrayNode parts;
            if(p.protocol.equals("gemini"))parts=(ArrayNode)body.path("contents").path(index).path("parts");
            else{ArrayNode list=(ArrayNode)body.path("messages");ObjectNode target=(ObjectNode)list.get(index+list.size()-messages.size());parts=target.putArray("content");parts.addObject().put("type","text").put("text",message.path("content").asText());}
            for(String image:images){
                image=MediaGateway.validatedFile(JSON.createObjectNode().put("file",image),true);
                String mime=image.substring(5,image.indexOf(';')),data=image.substring(image.indexOf(',')+1);
                if(p.protocol.equals("gemini"))parts.addObject().putObject("inlineData").put("mimeType",mime).put("data",data);
                else if(p.protocol.equals("anthropic"))parts.addObject().put("type","image").putObject("source").put("type","base64").put("media_type",mime).put("data",data);
                else {ObjectNode reference=parts.addObject().put("type","image_url").putObject("image_url").put("url",image);if(parameters.has("image_detail"))reference.set("detail",parameters.path("image_detail"));}
            }
        }
        boolean search=request.path("webSearch").asBoolean(false);
        boolean deepseekSearch=search&&p.id.equals("deepseek")&&model.equals("deepseek-v4-pro");
        boolean xaiSearch=search&&p.id.equals("xai")&&model.equals("grok-4.7");
        boolean responses = ApiRoutes.responses(p,model)||deepseekSearch||xaiSearch;
        if(search){
            if(responses)body.putArray("tools").addObject().put("type","web_search");
            else if(p.id.equals("gemini"))body.putArray("tools").addObject().putObject("google_search");
            else if(p.id.equals("anthropic"))body.putArray("tools").addObject().put("type","web_search_20250305").put("name","web_search").put("max_uses",5);
            else if(p.id.equals("qwen")&&Set.of("qwen3.8-max","qwen3.8-flash","qwen3.7-plus").contains(model))body.put("enable_search",true);
            else throw new Problem(400,"当前模型的联网搜索尚未接入，请关闭搜索或选择其他模型。");
        }
        if (responses) {

            body.set("input", body.remove("messages"));
            for(JsonNode msg:body.path("input"))if(msg.path("content").isArray())for(JsonNode part:msg.path("content")){
                ObjectNode entry=(ObjectNode)part;
                if(entry.path("type").asText().equals("image_url")){String url=entry.path("image_url").path("url").asText();JsonNode detail=entry.path("image_url").path("detail");entry.remove("image_url");entry.put("type","input_image").put("image_url",url);if(!detail.isMissingNode())entry.set("detail",detail);}
                else if(entry.path("type").asText().equals("text"))entry.put("type","input_text");
            }
            body.remove("reasoning_effort");
            if (!effort.isEmpty()) body.putObject("reasoning").put("effort", effort);
            if(model.startsWith("gpt-6")&&!effort.equals("none"))body.with("reasoning").put("summary","auto");
        }
        Parameters.chat(body,parameters,modelInfo,p,responses);
        if(responses)body.put("store",false);
        if(stream && !p.protocol.equals("gemini"))body.put("stream",true);
        return new Prepared(p,model,URI.create(deepseekSearch||xaiSearch?p.base+"/responses":ApiRoutes.chat(p,model,stream)),headers,body,parameters,responses);
    }
    static final class Prepared {
        final Provider provider; final String model; final URI uri; final Map<String,String> headers;
        final ObjectNode body, parameters; final boolean responses;
        Prepared(Provider provider,String model,URI uri,Map<String,String> headers,ObjectNode body,ObjectNode parameters,boolean responses){
            this.provider=provider;this.model=model;this.uri=uri;this.headers=headers;this.body=body;this.parameters=parameters;this.responses=responses;
        }
    }
    ObjectNode chat(JsonNode request) throws IOException {
        Prepared prepared=prepare(request,false);
        Reply reply=transport.send(prepared.uri,prepared.headers,JSON.writeValueAsBytes(prepared.body));
        checkStatus(prepared.provider,reply.status);
        return decode(prepared,reply.body);
    }
    static void checkStatus(Provider p,int status){
        if(status>=200 && status<300)return;
        String reason;
        switch(status){
            case 401:reason="API Key 无效或已过期";break;
            case 402:reason="账户余额不足，请在平台充值";break;
            case 403:reason="官方平台拒绝此请求，请核对模型接入条件、账号权限、地区或内容规则；无法仅凭 403 确定原因";break;
            case 404:reason="模型不存在或接口不可用，请检查模型 ID";break;
            case 429:reason="额度不足或请求过于频繁，请检查余额并稍后重试";break;
            case 400:case 422:reason="模型不支持此请求，请检查模型 ID、上下文长度及接口兼容性";break;
            default:reason="服务商暂时无法完成请求，请稍后重试";
        }
        throw new Problem(502,p.name+"："+reason+"（HTTP "+status+"）。");
    }
    static ObjectNode decode(Prepared prepared,byte[] bytes) throws IOException {
        Provider p=prepared.provider;String model=prepared.model;boolean responses=prepared.responses;ObjectNode parameters=prepared.parameters;
        JsonNode response;
        try { response = JSON.readTree(bytes); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new Problem(502, "服务商返回了无法识别的 JSON 响应。"); }
        if (response == null || !response.isObject()) throw new Problem(502, "服务商返回了无法识别的响应。");
        StringBuilder answer = new StringBuilder();
        JsonNode usage;
        if (p.protocol.equals("gemini")) {
            for (JsonNode part : response.path("candidates").path(0).path("content").path("parts"))
                if (!part.path("thought").asBoolean(false)) answer.append(part.path("text").asText(""));
            usage = response.path("usageMetadata");
        } else if (p.protocol.equals("anthropic")) {
            for (JsonNode part : response.path("content")) if (part.path("type").asText().equals("text")) answer.append(part.path("text").asText(""));
            usage = response.path("usage");
        } else if (responses) {
            for (JsonNode entry : response.path("output")) for (JsonNode part : entry.path("content")) if (part.path("type").asText().equals("output_text")) answer.append(part.path("text").asText(""));
            usage = response.path("usage");
        } else {
            JsonNode content = response.path("choices").path(0).path("message").path("content");
            if (content.isTextual()) answer.append(content.asText());
            else if (content.isArray()) for (JsonNode part : content) answer.append(part.path("text").asText(""));
            usage = response.path("usage");
        }
        if (answer.toString().isBlank()) throw new Problem(502, "服务商未返回文本，可能触发内容过滤或该模型不支持文本对话。");
        ObjectNode out = JSON.createObjectNode().put("text", answer.toString()).put("provider", p.id).put("model", model);
        if (usage.isObject()) out.set("usage", usage);
        ObjectNode details=out.putObject("details");
        if(parameters.has("logprobs")||parameters.has("top_logprobs")){
            if(responses){ArrayNode logs=details.putArray("logprobs");for(JsonNode v:response.path("output"))for(JsonNode c:v.path("content"))if(c.has("logprobs"))logs.add(c.path("logprobs"));}
            else if(response.path("choices").path(0).has("logprobs"))details.set("logprobs",response.path("choices").path(0).path("logprobs"));
        }
        if(p.protocol.equals("gemini"))SearchMetadata.gemini(response.path("candidates").path(0).path("groundingMetadata"),details);
        if(response.has("citations"))details.set("citations",response.path("citations"));
        if(responses)for(JsonNode entry:response.path("output"))SearchMetadata.responses(entry,details);
        if(p.protocol.equals("anthropic"))for(JsonNode part:response.path("content")){if(part.path("type").asText().equals("server_tool_use")&&part.path("name").asText().equals("web_search"))details.put("webSearchUsed",true);for(JsonNode citation:part.path("citations"))if(citation.path("type").asText().equals("web_search_result_location"))details.withArray("webCitations").addObject().put("url",citation.path("url").asText()).put("title",citation.path("title").asText()).put("end_index",-1);}
        if(responses) for(JsonNode entry:response.path("output")) for(JsonNode part:entry.path("content"))
            for(JsonNode citation:part.path("annotations")) if(citation.path("type").asText().equals("url_citation")) details.withArray("webCitations").add(citation);
        return out;
    }
    private static Reply https(URI uri, Map<String, String> headers, byte[] body) throws IOException {
        ApiRoutes.requireOfficial(uri);
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        headers.forEach(connection::setRequestProperty);
        try {
            try (OutputStream stream = connection.getOutputStream()) { stream.write(body); }
            int status = connection.getResponseCode();
            // Provider error bodies may contain credentials or private prompts: never forward or log them.
            if (status < 200 || status >= 300) return new Reply(status, new byte[0]);
            try (InputStream stream = connection.getInputStream()) {
                byte[] result = stream.readNBytes(2 * 1024 * 1024 + 1);
                if (result.length > 2 * 1024 * 1024) throw new Problem(502, "服务商响应过大，请缩短请求。");
                return new Reply(status, result);
            }
        } finally { connection.disconnect(); }
    }
}
