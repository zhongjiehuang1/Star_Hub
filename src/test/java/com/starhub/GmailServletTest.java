package com.starhub;

import org.junit.jupiter.api.Test;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GmailServletTest {
    private static final String GEN="ed8c456d-8f25-4a49-9f0f-ef39dc75323f";
    private static final byte[] VAULT=new byte[32];
    private static final class Backend implements GmailServlet.Backend {
        GmailCredentialStore.Owner user=new GmailCredentialStore.Owner(42,GEN);GmailCredentialStore.Stored row;GmailAuthorizationIntents.Intent intent;int saves;
        public GmailCredentialStore.Owner signedIn(HttpSession s){return s==null?null:user;}
        public boolean accountMatches(GmailCredentialStore.Owner owner,String email){return owner.userId()==42&&email.equals("test@example.test");}
        public GmailSecurity.Config config(){return new GmailSecurity.Config("1234-example.apps.googleusercontent.com","client-secret",URI.create("https://starhub.zhongjiehuang.com/api/gmail/callback"));}
        public byte[] vault(){return VAULT.clone();}
        public GmailCredentialStore.Stored read(GmailCredentialStore.Owner o){return row;}
        public void save(GmailCredentialStore.Owner o,String token,byte[] vault,long now)throws Exception{row=new GmailCredentialStore.Stored("CONNECTED",GmailSecurity.seal(token,vault,o.userId(),o.generation(),"refresh-token"));saves++;}
        public GmailAuthorizationIntents.Intent intent(GmailCredentialStore.Owner o){return intent;}
        public void begin(GmailCredentialStore.Owner o,String op,long now){intent=new GmailAuthorizationIntents.Intent(op,"AUTHORIZING");}
        public void exchange(GmailCredentialStore.Owner o,String op,long now){if(intent==null||!intent.operation().equals(op)||!intent.status().equals("AUTHORIZING"))throw new IllegalStateException();intent=new GmailAuthorizationIntents.Intent(op,"EXCHANGING");}
        public void unknown(GmailCredentialStore.Owner o,String op,long now){intent=new GmailAuthorizationIntents.Intent(op,"UNKNOWN");}
        public String accept(GmailCredentialStore.Owner o,String op,String token,byte[] vault,long now)throws Exception{
            String status=intent.status().equals("EXCHANGING")?"CONNECTED":"REVOKING";row=new GmailCredentialStore.Stored(status,GmailSecurity.seal(token,vault,o.userId(),o.generation(),"refresh-token"));intent=new GmailAuthorizationIntents.Intent(op,status);saves++;return status;}
        public boolean cancel(GmailCredentialStore.Owner o,long now){if(intent==null)return true;if(intent.status().equals("AUTHORIZING")){intent=null;return true;}if(intent.status().equals("EXCHANGING"))intent=new GmailAuthorizationIntents.Intent(intent.operation(),"CANCEL_PENDING");return false;}
        public void acknowledgeExternalRevocation(GmailCredentialStore.Owner o){if(intent==null||!intent.status().equals("UNKNOWN"))throw new IllegalStateException();intent=null;}
        public GmailCredentialStore.Stored beginDisconnect(GmailCredentialStore.Owner o,long now){if(row==null)return null;row=new GmailCredentialStore.Stored("REVOKING",row.credential());return row;}
        public boolean finishDisconnect(GmailCredentialStore.Owner o,GmailSecurity.Sealed expected){if(row==null||!MessageDigestEqual(row.credential(),expected))return false;row=null;intent=null;return true;}
        private boolean MessageDigestEqual(GmailSecurity.Sealed a,GmailSecurity.Sealed b){return java.security.MessageDigest.isEqual(a.nonce(),b.nonce())&&java.security.MessageDigest.isEqual(a.ciphertext(),b.ciphertext());}
    }
    private static final class Google implements GmailTransport {
        int exchanges,refreshes,revokes,searches,reads;boolean revokeOk=true,exchangeFails=false;Runnable onRead=()->{},onExchange=()->{};
        public Reply request(String method,URI url,String bearer,String form)throws IOException{
            String body;
            if(url.toString().equals(GmailSecurity.TOKEN_URL)){
                if(form.contains("authorization_code")){exchanges++;onExchange.run();if(exchangeFails)throw new IOException("Response lost");body="{\"scope\":\""+GmailSecurity.READ_ONLY_SCOPE+"\",\"refresh_token\":\"refresh-secret\"}";}
                else {refreshes++;body="{\"token_type\":\"Bearer\",\"access_token\":\"access-secret\"}";}
            }else if(url.toString().equals(GmailSecurity.REVOKE_URL)){revokes++;return new Reply(revokeOk?200:503,new byte[0]);}
            else if(url.getPath().endsWith("/messages")){searches++;onRead.run();body="{\"messages\":[{\"id\":\"abc123\"}]}";}
            else if(url.getPath().endsWith("/messages/abc123")){reads++;body="{\"id\":\"abc123\",\"snippet\":\"hello\",\"payload\":{\"mimeType\":\"text/plain\",\"body\":{\"data\":\"aGVsbG8\"}}}";}
            else throw new IOException("Unexpected URL");
            return new Reply(200,body.getBytes(StandardCharsets.UTF_8));
        }
    }
    private record Output(HttpServletResponse response,ByteArrayOutputStream bytes) { }
    private static Output output()throws Exception{
        HttpServletResponse response=mock(HttpServletResponse.class);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(WriteListener l){}public void write(int b){bytes.write(b);}});
        return new Output(response,bytes);
    }
    private static HttpSession session(){HttpSession session=mock(HttpSession.class);Map<String,Object> attrs=new HashMap<>();
        attrs.put("starhub.account.email","test@example.test");when(session.getAttribute(anyString())).thenAnswer(x->attrs.get(x.getArgument(0)));
        doAnswer(x->{attrs.put(x.getArgument(0),x.getArgument(1));return null;}).when(session).setAttribute(anyString(),any());
        doAnswer(x->{attrs.remove(x.getArgument(0));return null;}).when(session).removeAttribute(anyString());return session;}
    private static HttpServletRequest request(String path,HttpSession session,String body)throws Exception{
        HttpServletRequest req=mock(HttpServletRequest.class);when(req.getPathInfo()).thenReturn(path);when(req.getSession(false)).thenReturn(session);
        when(req.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(req.getHeader("X-Starhub-Expected-Account")).thenReturn("test@example.test");when(req.getHeader("X-Starhub-Client")).thenReturn("web");when(req.getContentType()).thenReturn("application/json");
        byte[] bytes=(body==null?"{}":body).getBytes(StandardCharsets.UTF_8);ByteArrayInputStream in=new ByteArrayInputStream(bytes);
        when(req.getInputStream()).thenReturn(new ServletInputStream(){public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){}public int read(){return in.read();}});return req;
    }
    private static String state(String body)throws Exception{
        URI uri=URI.create(ModelGateway.JSON.readTree(body).path("authorizationUrl").asText());
        for(String entry:uri.getRawQuery().split("&")){String[] pair=entry.split("=",2);if(pair[0].equals("state"))return URLDecoder.decode(pair[1],StandardCharsets.UTF_8);}throw new AssertionError("state missing");
    }

    @Test void oauthStateIsSingleUseAndTokensNeverAppearInBrowserResponse()throws Exception{
        Backend b=new Backend();Google google=new Google();var servlet=new GmailServlet(b,google,()->1000);HttpSession session=session();
        Output start=output();servlet.doPost(request("/start",session,"{}"),start.response());verify(start.response()).setStatus(200);
        String startText=start.bytes().toString(StandardCharsets.UTF_8);assertFalse(startText.contains("client-secret"));String state=state(startText);
        HttpServletRequest bad=request("/callback",session,null);when(bad.getParameter("state")).thenReturn("x".repeat(43));Output wrong=output();servlet.doGet(bad,wrong.response());
        verify(wrong.response()).setHeader("Location","/?gmail=error");assertEquals(0,google.exchanges);
        HttpServletRequest callback=request("/callback",session,null);when(callback.getParameter("state")).thenReturn(state);when(callback.getParameter("code")).thenReturn("code-123");Output done=output();servlet.doGet(callback,done.response());
        verify(done.response()).setHeader("Location","https://starhub.zhongjiehuang.com/?gmail=connected");assertEquals(1,google.exchanges);assertEquals(1,b.saves);
        assertFalse(done.bytes().toString(StandardCharsets.UTF_8).contains("refresh-secret"));assertFalse(b.row.toString().contains("refresh-secret"));
        Output replay=output();servlet.doGet(callback,replay.response());verify(replay.response()).setHeader("Location","/?gmail=error");assertEquals(1,google.exchanges);
    }

    @Test void searchAndMessageAreExplicitReadOnlyAndNeverExposeTokens()throws Exception{
        Backend b=new Backend();b.save(b.user,"refresh-secret",VAULT,1);Google google=new Google();var servlet=new GmailServlet(b,google,()->1000);HttpSession session=session();
        Output search=output();servlet.doPost(request("/search",session,"{\"query\":\"from:person@example.test\"}"),search.response());verify(search.response()).setStatus(200);
        String result=search.bytes().toString(StandardCharsets.UTF_8);assertTrue(result.contains("abc123"));assertFalse(result.contains("refresh-secret"));assertFalse(result.contains("access-secret"));
        assertEquals(1,google.searches);assertEquals(1,google.reads);Output msg=output();servlet.doPost(request("/message",session,"{\"id\":\"abc123\"}"),msg.response());
        assertTrue(msg.bytes().toString(StandardCharsets.UTF_8).contains("hello"));assertEquals(2,google.reads);
    }

    @Test void disconnectKeepsUnconfirmedRevocationDisabled()throws Exception{
        Backend b=new Backend();b.save(b.user,"refresh-secret",VAULT,1);Google google=new Google();google.revokeOk=false;var servlet=new GmailServlet(b,google,()->1000);HttpSession session=session();
        Output disconnect=output();servlet.doPost(request("/disconnect",session,"{}"),disconnect.response());verify(disconnect.response()).setStatus(202);assertEquals("REVOKING",b.row.status());
        Output search=output();servlet.doPost(request("/search",session,"{}"),search.response());verify(search.response()).setStatus(503);assertEquals(0,google.searches);
        google.revokeOk=true;Output retry=output();servlet.doPost(request("/disconnect",session,"{}"),retry.response());verify(retry.response()).setStatus(200);assertNull(b.row);
    }

    @Test void existingConnectionMustBeDisconnectedBeforeAnotherAuthorization()throws Exception{
        Backend b=new Backend();b.save(b.user,"refresh-secret",VAULT,1);Google google=new Google();var servlet=new GmailServlet(b,google,()->1000);
        Output start=output();servlet.doPost(request("/start",session(),"{}"),start.response());
        verify(start.response()).setStatus(409);assertFalse(start.bytes().toString(StandardCharsets.UTF_8).contains("authorizationUrl"));assertEquals(0,google.exchanges);
    }

    @Test void expiredSessionAfterExchangeRetainsReceiptButDoesNotDiscloseSuccess()throws Exception{
        Backend b=new Backend();Google google=new Google();var servlet=new GmailServlet(b,google,()->1000);HttpSession session=session();
        Output start=output();servlet.doPost(request("/start",session,"{}"),start.response());String state=state(start.bytes().toString(StandardCharsets.UTF_8));
        google.onExchange=()->b.user=null;HttpServletRequest callback=request("/callback",session,null);when(callback.getParameter("state")).thenReturn(state);when(callback.getParameter("code")).thenReturn("code-123");
        Output result=output();servlet.doGet(callback,result.response());verify(result.response()).setHeader("Location","/?gmail=error");assertEquals("CONNECTED",b.intent.status());assertEquals(1,b.saves);
    }

    @Test void disconnectDuringExchangeCannotActivateLateToken()throws Exception{
        Backend b=new Backend();Google google=new Google();var servlet=new GmailServlet(b,google,()->1000);HttpSession session=session();
        Output start=output();servlet.doPost(request("/start",session,"{}"),start.response());String state=state(start.bytes().toString(StandardCharsets.UTF_8));
        google.onExchange=()->b.cancel(b.user,1001);HttpServletRequest callback=request("/callback",session,null);when(callback.getParameter("state")).thenReturn(state);when(callback.getParameter("code")).thenReturn("code-123");
        Output result=output();servlet.doGet(callback,result.response());verify(result.response()).setHeader("Location","/?gmail=error");assertEquals("REVOKING",b.row.status());assertEquals(1,google.exchanges);
    }

    @Test void lostGoogleResponseIsUnknownAndNeverReplaysCode()throws Exception{
        Backend b=new Backend();Google google=new Google();google.exchangeFails=true;var servlet=new GmailServlet(b,google,()->1000);HttpSession session=session();
        Output start=output();servlet.doPost(request("/start",session,"{}"),start.response());String state=state(start.bytes().toString(StandardCharsets.UTF_8));
        HttpServletRequest callback=request("/callback",session,null);when(callback.getParameter("state")).thenReturn(state);when(callback.getParameter("code")).thenReturn("code-123");
        Output result=output();servlet.doGet(callback,result.response());assertEquals("UNKNOWN",b.intent.status());servlet.doGet(callback,output().response());assertEquals(1,google.exchanges);assertNull(b.row);
    }

    @Test void inconsistentSessionAttributesCannotSelectDifferentAccount()throws Exception{
        Backend b=new Backend();b.save(b.user,"refresh-secret",VAULT,1);b.user=new GmailCredentialStore.Owner(43,GEN);Google google=new Google();
        var servlet=new GmailServlet(b,google,()->1000);Output out=output();servlet.doPost(request("/search",session(),"{}"),out.response());
        verify(out.response()).setStatus(401);assertEquals(0,google.searches);assertFalse(out.bytes().toString(StandardCharsets.UTF_8).contains("abc123"));
    }

    @Test void staleTabCannotReadAnotherSessionAccount()throws Exception{
        Backend b=new Backend();b.save(b.user,"refresh-secret",VAULT,1);Google google=new Google();var servlet=new GmailServlet(b,google,()->1000);HttpSession session=session();
        HttpServletRequest stale=request("/search",session,"{}");when(stale.getHeader("X-Starhub-Expected-Account")).thenReturn("old-account@example.test");
        Output out=output();servlet.doPost(stale,out.response());verify(out.response()).setStatus(401);assertEquals(0,google.searches);
        HttpServletRequest staleStatus=request("/status",session,"{}");when(staleStatus.getHeader("X-Starhub-Expected-Account")).thenReturn("old-account@example.test");
        Output status=output();servlet.doGet(staleStatus,status.response());verify(status.response()).setStatus(401);
    }

    @Test void sessionAccountSwitchDuringReadSuppressesMailboxResult()throws Exception{
        Backend b=new Backend();b.save(b.user,"refresh-secret",VAULT,1);Google google=new Google();var servlet=new GmailServlet(b,google,()->1000);HttpSession session=session();
        google.onRead=()->session.setAttribute("starhub.account.email","other@example.test");Output out=output();servlet.doPost(request("/search",session,"{}"),out.response());
        verify(out.response()).setStatus(401);assertFalse(out.bytes().toString(StandardCharsets.UTF_8).contains("abc123"));
    }

    @Test void rejectsBadOriginAndAccountSwitchBeforeReturningMail()throws Exception{
        Backend b=new Backend();b.save(b.user,"refresh-secret",VAULT,1);Google google=new Google();var servlet=new GmailServlet(b,google,()->1000);HttpSession session=session();
        HttpServletRequest bad=request("/search",session,"{}");when(bad.getHeader("Origin")).thenReturn("https://evil.example");Output blocked=output();servlet.doPost(bad,blocked.response());verify(blocked.response()).setStatus(403);assertEquals(0,google.searches);
        google.onRead=()->b.user=new GmailCredentialStore.Owner(43,GEN);Output changed=output();servlet.doPost(request("/search",session,"{}"),changed.response());verify(changed.response()).setStatus(401);assertFalse(changed.bytes().toString(StandardCharsets.UTF_8).contains("abc123"));
    }
}
