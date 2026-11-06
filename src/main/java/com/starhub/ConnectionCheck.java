package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.URI;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Read-only credential check. Never generates model output or follows redirects. */
final class ConnectionCheck {
    interface Transport { ModelGateway.Reply get(URI uri,Map<String,String> headers)throws IOException; }
    private final Transport transport;
    ConnectionCheck(){this(ConnectionCheck::https);}
    ConnectionCheck(Transport transport){this.transport=transport;}
    ObjectNode run(JsonNode request)throws IOException {
        ModelGateway.Provider p=ModelGateway.PROVIDERS.get(request.path("provider").asText());
        if(p==null)throw new ModelGateway.Problem(400,"请选择支持的官方厂商。");
        String key=request.path("apiKey").asText().trim();
        if(!key.matches("[\\x21-\\x7e]{1,4096}"))throw new ModelGateway.Problem(400,"请填写有效的 API Key。");
        // Beijing Model Studio's listing API requires a workspace-specific host.
        // Do not invent a host or send a paid generation as a substitute.
        if(p.id.equals("qwen"))return ModelGateway.JSON.createObjectNode().put("status","unsupported");
        Map<String,String> headers=new LinkedHashMap<>();headers.put("Accept","application/json");
        if(p.protocol.equals("gemini"))headers.put("x-goog-api-key",key);
        else if(p.protocol.equals("anthropic")){headers.put("x-api-key",key);headers.put("anthropic-version","2023-06-01");}
        else headers.put("Authorization","Bearer "+key);
        ModelGateway.Reply reply=transport.get(URI.create(ApiRoutes.models(p)),headers);
        if(reply.status==401||reply.status==403)return ModelGateway.JSON.createObjectNode().put("status","rejected").put("reason","credentials");
        if(reply.status==404||reply.status==405)return ModelGateway.JSON.createObjectNode().put("status","unsupported");
        if(reply.status!=200)throw new ModelGateway.Problem(502,"官方 API 检查失败，请稍后重试。");
        JsonNode body=ModelGateway.JSON.readTree(reply.body);
        if(body==null||!(body.path("data").isArray()||body.path("models").isArray()))throw new ModelGateway.Problem(502,"官方 API 检查响应无效。");
        return ModelGateway.JSON.createObjectNode().put("status","verified");
    }
    private static ModelGateway.Reply https(URI uri,Map<String,String> headers)throws IOException {
        ApiRoutes.requireOfficial(uri);HttpsURLConnection c=(HttpsURLConnection)uri.toURL().openConnection();
        c.setRequestMethod("GET");c.setConnectTimeout(8000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);headers.forEach(c::setRequestProperty);
        try{int status=c.getResponseCode();if(status!=200)return new ModelGateway.Reply(status,new byte[0]);
            try(InputStream in=c.getInputStream()){byte[] bytes=in.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024)throw new IOException("Response too large");return new ModelGateway.Reply(status,bytes);}
        }finally{c.disconnect();}
    }
}
