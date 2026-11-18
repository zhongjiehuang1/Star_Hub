package com.starhub;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GmailClientTest {
    private static final String SCOPE=GmailSecurity.READ_ONLY_SCOPE;
    private static GmailTransport.Reply json(String value){return new GmailTransport.Reply(200,value.getBytes(StandardCharsets.UTF_8));}
    private static String text64(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private static final GmailSecurity.Config CONFIG=new GmailSecurity.Config("1234-example.apps.googleusercontent.com","sensitive-secret",URI.create("https://starhub.zhongjiehuang.com/api/gmail/callback"));

    @Test void oauthUsesOnlyFixedEndpointsAndNeverPutsSecretsInUrls() throws Exception {
        var challenge=GmailSecurity.challenge(0);List<String> calls=new ArrayList<>();
        GmailTransport fake=(method,url,bearer,form)->{calls.add(method+" "+url);assertEquals("POST",method);assertNull(bearer);
            if(url.toString().equals(GmailSecurity.REVOKE_URL)){assertTrue(form.startsWith("token="));return json("{}");}
            assertEquals(GmailSecurity.TOKEN_URL,url.toString());assertTrue(form.contains("client_secret="));
            if(form.contains("authorization_code")){assertTrue(form.contains("code_verifier="));return json("{\"scope\":\""+SCOPE+"\",\"refresh_token\":\"refresh-test\"}");}
            return json("{\"scope\":\""+SCOPE+"\",\"token_type\":\"Bearer\",\"access_token\":\"access-test\"}");};
        var oauth=new GmailOAuthClient(fake);assertEquals("refresh-test",oauth.exchange(CONFIG,challenge,"4/code"));
        assertEquals("access-test",oauth.refresh(CONFIG,"refresh-test"));assertTrue(oauth.revoke("refresh-test"));
        assertEquals(3,calls.size());for(String call:calls){assertFalse(call.contains("sensitive-secret"));assertFalse(call.contains("refresh-test"));assertFalse(call.contains("access-test"));}
    }

    @Test void unexpectedOAuthScopesAndMissingRefreshAreRejected() throws Exception {
        var challenge=GmailSecurity.challenge(0);
        var oauth=new GmailOAuthClient((m,u,b,f)->json("{\"scope\":\""+SCOPE+" https://www.googleapis.com/auth/gmail.send\",\"refresh_token\":\"token\"}"));
        assertThrows(IOException.class,()->oauth.exchange(CONFIG,challenge,"code"));
        var missing=new GmailOAuthClient((m,u,b,f)->json("{\"scope\":\""+SCOPE+"\"}"));
        assertThrows(IOException.class,()->missing.exchange(CONFIG,challenge,"code"));
        var revoked=new GmailOAuthClient((m,u,b,f)->new GmailTransport.Reply(503,new byte[0]));assertFalse(revoked.revoke("token"));
    }

    @Test void searchRetrievesOnlyBoundedMetadataAndSelectedMessageBody() throws Exception {
        List<URI> calls=new ArrayList<>();String mail="Hello world\nPlease review the document.";
        GmailTransport fake=(method,url,bearer,form)->{calls.add(url);assertEquals("GET",method);assertEquals("access-test",bearer);assertNull(form);assertEquals("gmail.googleapis.com",url.getHost());
            if(url.getPath().endsWith("/messages"))return json("{\"messages\":[{\"id\":\"abc123\"}],\"nextPageToken\":\"next123\"}");
            ObjectNode item=ModelGateway.JSON.createObjectNode().put("id","abc123").put("snippet","A snippet");
            ObjectNode payload=item.putObject("payload").put("mimeType","multipart/alternative");
            payload.putArray("headers").addObject().put("name","From").put("value","Sender <sender@example.test>");
            var parts=payload.putArray("parts");parts.addObject().put("mimeType","text/html").putObject("body").put("data",text64("<script>secret</script>"));
            parts.addObject().put("mimeType","text/plain").putObject("body").put("data",text64(mail));
            return new GmailTransport.Reply(200,ModelGateway.JSON.writeValueAsBytes(item));};
        var mailbox=new GmailMailbox(fake);var page=mailbox.search("access-test","from:person@example.test is:unread",5,"");
        assertEquals(1,page.messages().size());assertEquals("next123",page.nextPageToken());assertEquals("",page.messages().get(0).body());
        assertEquals(mail,mailbox.message("access-test","abc123",true).body());
        assertTrue(calls.get(0).getRawQuery().contains("from%3Aperson%40example.test"));
        assertTrue(calls.get(1).getRawQuery().contains("format=metadata"));assertTrue(calls.get(2).getRawQuery().contains("format=full"));
        assertFalse(page.toString().contains("sender@example.test"));
        assertThrows(IOException.class,()->mailbox.message("access-test","../send",true));
        assertThrows(IOException.class,()->mailbox.search("access-test","q\ninjection",5,""));
    }

    @Test void oversizedMimeAndAttachmentsAreNotReadAsBody() throws Exception {
        ObjectNode item=ModelGateway.JSON.createObjectNode().put("id","abc");
        ObjectNode payload=item.putObject("payload").put("mimeType","multipart/mixed");
        var parts=payload.putArray("parts");parts.addObject().put("filename","file.txt").put("mimeType","text/plain").putObject("body").put("data",text64("secret attachment"));
        parts.addObject().put("mimeType","text/plain").putObject("body").put("data",text64("a".repeat(20_001)));
        var mailbox=new GmailMailbox((m,u,b,f)->new GmailTransport.Reply(200,ModelGateway.JSON.writeValueAsBytes(item)));
        var message=mailbox.message("access-test","abc",true);assertEquals(20_000,message.body().length());assertTrue(message.truncated());assertFalse(message.body().contains("secret attachment"));
    }

    @Test void transportRejectsArbitraryHostsMethodsAndPathsBeforeNetwork() {
        var transport=new GmailTransport.Https();
        assertThrows(IOException.class,()->transport.request("GET",URI.create("https://evil.example/gmail/v1/users/me/messages"),"token",null));
        assertThrows(IOException.class,()->transport.request("POST",URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send"),null,""));
        assertThrows(IOException.class,()->transport.request("GET",URI.create("https://gmail.googleapis.com/gmail/v1/users/me/settings"),"token",null));
        assertThrows(IOException.class,()->transport.request("GET",URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages"),"bad\nheader",null));
    }
}
