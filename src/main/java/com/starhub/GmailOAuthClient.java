package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Google OAuth exchange/refresh/revocation. A lost reply is never replayed here. */
final class GmailOAuthClient {
    private final GmailTransport transport;
    GmailOAuthClient(GmailTransport transport){this.transport=java.util.Objects.requireNonNull(transport);}

    /** Return only the refresh token to trusted server-side storage code. */
    String exchange(GmailSecurity.Config config, GmailSecurity.Challenge challenge, String code) throws IOException {
        if(config==null||challenge==null||!safe(code,2048)||challenge.verifier()==null||
                !challenge.verifier().matches("[A-Za-z0-9_-]{43,128}"))throw new IOException("Invalid Gmail authorization response");
        Map<String,String> fields=new LinkedHashMap<>();
        fields.put("client_id",config.clientId());fields.put("client_secret",config.clientSecret());
        fields.put("code",code);fields.put("code_verifier",challenge.verifier());
        fields.put("redirect_uri",config.redirectUri().toString());fields.put("grant_type","authorization_code");
        JsonNode response=post(fields);
        validateScope(response,true);
        String token=response.path("refresh_token").asText("");
        if(!safe(token,4096))throw new IOException("Google did not provide a Gmail refresh credential");
        return token;
    }

    /** Access tokens are transient and must never be sent to a browser or log. */
    String refresh(GmailSecurity.Config config, String refreshToken) throws IOException {
        if(config==null||!safe(refreshToken,4096))throw new IOException("Invalid Gmail credential");
        Map<String,String> fields=new LinkedHashMap<>();
        fields.put("client_id",config.clientId());fields.put("client_secret",config.clientSecret());
        fields.put("refresh_token",refreshToken);fields.put("grant_type","refresh_token");
        JsonNode response=post(fields);validateScope(response,false);
        String access=response.path("access_token").asText("");
        if(!safe(access,4096)||!"Bearer".equalsIgnoreCase(response.path("token_type").asText("")))
            throw new IOException("Google returned an invalid Gmail access credential");
        return access;
    }

    /** A non-success/unknown result must leave the local credential disabled. */
    boolean revoke(String refreshToken) throws IOException {
        if(!safe(refreshToken,4096))throw new IOException("Invalid Gmail credential");
        GmailTransport.Reply reply=transport.request("POST",URI.create(GmailSecurity.REVOKE_URL),null,
                GmailSecurity.form(Map.of("token",refreshToken)));
        return reply.status()==200;
    }

    private JsonNode post(Map<String,String> fields) throws IOException {
        GmailTransport.Reply response=transport.request("POST",URI.create(GmailSecurity.TOKEN_URL),null,GmailSecurity.form(fields));
        if(response.status()!=200)throw new IOException("Google Gmail authorization failed");
        JsonNode json;try{json=ModelGateway.JSON.readTree(response.body());}catch(Exception invalid){throw new IOException("Google returned an invalid authorization response");}
        if(json==null||!json.isObject())throw new IOException("Google returned an invalid authorization response");
        return json;
    }
    private static void validateScope(JsonNode json, boolean required) throws IOException {
        String scope=json.path("scope").asText("");
        if((required&&scope.isBlank())||(!scope.isBlank()&&!GmailSecurity.READ_ONLY_SCOPE.equals(scope)))
            throw new IOException("Google granted unexpected Gmail permissions");
    }
    static boolean safe(String value,int max){return value!=null&&!value.isEmpty()&&value.length()<=max&&value.chars().allMatch(c->c>=33&&c<=126);}
}
