package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/** Confidential, owner- and answer-bound Kimi continuation; no SQL or new keys. */
final class KimiContext {
    static final int MAX_TOKEN=140000, MAX_RAW=MAX_TOKEN*3/4, MAX_REASON_BYTES=100000;
    private static final String LABEL="starhub-kimi-context-v1";
    private static final SecureRandom RANDOM=new SecureRandom();
    private KimiContext(){}
    static boolean supportsModel(String model){return Set.of("kimi-k3","kimi-k3.1-pro").contains(model);}
    static String principal(String identity) {
        if(identity==null)throw bad();
        String[] p=identity.split(":",-1);
        if(p.length==4 && p[0].equals("account") && !p[1].isBlank() && !p[2].isBlank() && !p[3].isBlank())return "account:"+p[1]+":"+p[2];
        if(p.length==3 && p[0].equals("guest") && !p[1].isBlank() && !p[2].isBlank())return identity;
        throw bad();
    }
    static byte[] configuredKey() {
        try {byte[] k=Base64.getDecoder().decode(AccountServlet.config().getProperty("vault.key",""));if(!Set.of(16,24,32).contains(k.length))throw bad();return k;}
        catch(Exception e){throw bad();}
    }
    private static boolean validUnicode(String value){
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;
    }
    private static String digest(String text)throws Exception{if(text==null||!validUnicode(text))throw bad();return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static String aad(String owner,String model){return LABEL+"\0"+owner+"\0"+model;}
    static String seal(byte[] key,String identity,String model,String answer,String reason) throws Exception {
        String owner=principal(identity);
        if(model==null||model.isBlank()||answer==null||answer.isBlank()||reason==null||reason.isBlank()||!validUnicode(reason)||reason.getBytes(StandardCharsets.UTF_8).length>MAX_REASON_BYTES)throw bad();
        ObjectNode body=ModelGateway.JSON.createObjectNode().put("version",1).put("owner",owner).put("provider","moonshot").put("model",model).put("answerSha256",digest(answer)).put("reasoning",reason);
        byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);byte[] ciphertext=AccountServlet.crypt(true,ModelGateway.JSON.writeValueAsBytes(body),key,nonce,aad(owner,model));
        byte[] raw=new byte[nonce.length+ciphertext.length];System.arraycopy(nonce,0,raw,0,nonce.length);System.arraycopy(ciphertext,0,raw,nonce.length,ciphertext.length);
        if(raw.length>MAX_RAW)throw bad();String token=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);if(token.length()>MAX_TOKEN)throw bad();return token;
    }
    static String open(byte[] key,String identity,String model,String answer,String token) {
        try {
            String owner=principal(identity);
            if(token==null||token.length()>MAX_TOKEN||!token.matches("[A-Za-z0-9_-]+"))throw bad();
            byte[] raw=Base64.getUrlDecoder().decode(token);if(raw.length<29||raw.length>MAX_RAW)throw bad();
            byte[] bytes=AccountServlet.crypt(false,java.util.Arrays.copyOfRange(raw,12,raw.length),key,java.util.Arrays.copyOf(raw,12),aad(owner,model));
            JsonNode v=ModelGateway.JSON.readTree(bytes);String reason=v.path("reasoning").asText("");
            if(v.path("version").asInt()!=1||!v.path("owner").asText().equals(owner)||!v.path("provider").asText().equals("moonshot")||!v.path("model").asText().equals(model)||!v.path("answerSha256").asText().equals(digest(answer))||!v.path("reasoning").isTextual()||reason.isBlank()||!validUnicode(reason)||reason.getBytes(StandardCharsets.UTF_8).length>MAX_REASON_BYTES)throw bad();
            return reason;
        } catch(Exception e){throw bad();}
    }
    static void hydrate(ObjectNode request,String identity,byte[] key) {
        boolean kimi=request.path("provider").asText().equals("moonshot");String model=request.path("model").asText();
        JsonNode messages=request.path("messages");if(!messages.isArray())return;
        for(JsonNode n:messages){
            if(!n.isObject())throw bad();ObjectNode m=(ObjectNode)n;
            if(m.has("providerReasoning")||m.has("reasoning_content"))throw bad();
            JsonNode ctx=m.path("providerContext");
            if(!ctx.isMissingNode()){
                if(!kimi||!m.path("role").asText().equals("assistant")||!ctx.isObject()||!m.path("provider").asText().equals("moonshot")||!m.path("model").asText().equals(model)||!ctx.path("provider").asText().equals("moonshot")||!ctx.path("model").asText().equals(model)||!ctx.path("token").isTextual()||!m.path("content").isTextual())throw bad();
                String reason=open(key,identity,model,m.path("content").asText(),ctx.path("token").asText());
                m.putObject("providerReasoning").put("provider","moonshot").put("model",model).put("content",reason);
            }
            m.remove("providerContext");
        }
    }
    static void protectResult(ObjectNode result,String identity,byte[] key) throws Exception {
        JsonNode r=result.path("providerReasoning");if(r.isMissingNode())return;
        String model=result.path("model").asText();
        try {
            if(!r.isObject()||!result.path("provider").asText().equals("moonshot")||!r.path("provider").asText().equals("moonshot")||!r.path("model").asText().equals(model)||!r.path("content").isTextual())throw bad();
            String token=seal(key,identity,model,result.path("text").asText(),r.path("content").asText());result.putObject("providerContext").put("provider","moonshot").put("model",model).put("token",token);
        } catch(Exception unavailable){result.remove("providerContext");result.put("continuationUnavailable",true);}
        finally {result.remove("providerReasoning");}
    }
    static void protectConfiguredResult(ObjectNode result,String identity){
        if(!result.has("providerReasoning"))return;
        try {protectResult(result,identity,configuredKey());}
        catch(Exception unavailable){result.remove("providerReasoning");result.remove("providerContext");result.put("continuationUnavailable",true);}
    }
    private static ModelGateway.Problem bad(){return new ModelGateway.Problem(400,"模型上下文不可用或不匹配；请新建对话，原记录会保留。 ");}
}
