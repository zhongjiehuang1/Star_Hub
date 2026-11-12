package com.starhub;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConnectionCheckTest {
    ObjectNode req(String provider){return ModelGateway.JSON.createObjectNode().put("provider",provider).put("apiKey","fixture-key");}
    ModelGateway.Reply reply(int code,String body){return new ModelGateway.Reply(code,body.getBytes(StandardCharsets.UTF_8));}
    @Test void usesOnlyOfficialModelListsAndProviderAuthentication()throws Exception{
        for(String provider:List.of("openai","anthropic","gemini","deepseek","moonshot","xai")){
            ObjectNode r=req(provider).put("baseUrl","https://example.invalid");
            ConnectionCheck check=new ConnectionCheck((uri,headers)->{
                assertEquals(ModelGateway.PROVIDERS.get(provider).base+"/models",uri.toString());assertFalse(uri.toString().contains("fixture-key"));assertDoesNotThrow(()->ApiRoutes.requireOfficial(uri));
                String name=provider.equals("gemini")?"x-goog-api-key":provider.equals("anthropic")?"x-api-key":"Authorization";
                assertEquals(name.equals("Authorization")?"Bearer fixture-key":"fixture-key",headers.get(name));
                if(provider.equals("anthropic"))assertEquals("2023-06-01",headers.get("anthropic-version"));
                return reply(200,provider.equals("gemini")?"{\"models\":[]}":"{\"data\":[]}");
            });assertEquals("verified",check.run(r).path("status").asText());
        }
    }
    @Test void missingUnsupportedAndMalformedCredentialsNeverReachTransport()throws Exception{
        AtomicInteger calls=new AtomicInteger();ConnectionCheck check=new ConnectionCheck((u,h)->{calls.incrementAndGet();return reply(200,"{\"data\":[]}");});
        assertEquals("unsupported",check.run(req("qwen")).path("status").asText());
        for(String p:List.of("openrouter","custom",""))assertThrows(ModelGateway.Problem.class,()->check.run(req(p)));
        for(String key:List.of("","two words","a\r\nb","密钥","x".repeat(4097)))assertThrows(ModelGateway.Problem.class,()->check.run(req("openai").put("apiKey",key)));
        assertEquals(0,calls.get());
    }
    @Test void failedChecksNeverPublishUpstreamBodiesAndDoNotRetry()throws Exception{
        for(int status:List.of(401,403,404,405,429,500,302)){
            AtomicInteger calls=new AtomicInteger();ConnectionCheck check=new ConnectionCheck((u,h)->{calls.incrementAndGet();return reply(status,"private-provider-detail-fixture-key");});
            if(status==401||status==403){ObjectNode r=check.run(req("deepseek"));assertEquals("rejected",r.path("status").asText());assertFalse(r.toString().contains("fixture-key"));}
            else if(status==404||status==405)assertEquals("unsupported",check.run(req("deepseek")).path("status").asText());
            else assertFalse(assertThrows(ModelGateway.Problem.class,()->check.run(req("deepseek"))).getMessage().contains("fixture-key"));
            assertEquals(1,calls.get());
        }
        assertThrows(ModelGateway.Problem.class,()->new ConnectionCheck((u,h)->reply(200,"{}")).run(req("openai")));
    }
}
