package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/** Optional read-only Gmail integration. Not registered until deployment is reviewed. */
public final class GmailServlet extends HttpServlet {
    private static final String PENDING="starhub.gmail.oauth.pending.v1";
    private static final Semaphore READS=new Semaphore(4);
    interface Backend {
        GmailCredentialStore.Owner signedIn(HttpSession session) throws Exception;
        boolean accountMatches(GmailCredentialStore.Owner owner,String expectedEmail) throws Exception;
        GmailSecurity.Config config() throws Exception;
        byte[] vault() throws Exception;
        GmailCredentialStore.Stored read(GmailCredentialStore.Owner owner) throws Exception;
        GmailAuthorizationIntents.Intent intent(GmailCredentialStore.Owner owner) throws Exception;
        void begin(GmailCredentialStore.Owner owner,String operation,long now) throws Exception;
        void exchange(GmailCredentialStore.Owner owner,String operation,long now) throws Exception;
        void unknown(GmailCredentialStore.Owner owner,String operation,long now) throws Exception;
        String accept(GmailCredentialStore.Owner owner,String operation,String token,byte[] vault,long now) throws Exception;
        boolean cancel(GmailCredentialStore.Owner owner,long now) throws Exception;
        void acknowledgeExternalRevocation(GmailCredentialStore.Owner owner) throws Exception;
        GmailCredentialStore.Stored beginDisconnect(GmailCredentialStore.Owner owner,long now) throws Exception;
        boolean finishDisconnect(GmailCredentialStore.Owner owner,GmailSecurity.Sealed expected) throws Exception;
    }
    private record Pending(byte[] stateHash,byte[] nonce,byte[] verifier,long created,long userId,String generation,String operation) implements Serializable {
        private static final long serialVersionUID=1L;
        Pending {stateHash=stateHash.clone();nonce=nonce.clone();verifier=verifier.clone();}
        @Override public String toString(){return "GmailPending[redacted]";}
    }
    private final Backend backend;private final GmailOAuthClient oauth;private final GmailMailbox mailbox;private final LongSupplier clock;
    public GmailServlet(){this(new GmailRuntime(),new GmailTransport.Https(),System::currentTimeMillis);}
    GmailServlet(Backend backend,GmailTransport transport,LongSupplier clock){this.backend=backend;this.oauth=new GmailOAuthClient(transport);this.mailbox=new GmailMailbox(transport);this.clock=clock;}

    private void json(HttpServletResponse response,int status,Object result) throws IOException {
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        ModelGateway.JSON.writeValue(response.getOutputStream(),result);
    }
    private void fail(HttpServletResponse response,int status,String message) throws IOException {
        json(response,status,ModelGateway.JSON.createObjectNode().put("error",message));
    }
    private void callbackFailure(HttpServletResponse response) {
        // Do not leave the authorization code/state in the visible callback URL.
        response.setHeader("Cache-Control","no-store");response.setStatus(303);response.setHeader("Location","/?gmail=error");
    }
    private static final class AccountChanged extends IOException { }
    private GmailCredentialStore.Owner sessionOwner(HttpServletRequest request) throws Exception {
        try{return backend.signedIn(request.getSession(false));}catch(StorageFailure failure){if(failure.status==401)return null;throw failure;}
    }
    private GmailCredentialStore.Owner owner(HttpServletRequest request) throws Exception {
        HttpSession session=request.getSession(false);String expected=request.getHeader("X-Starhub-Expected-Account");
        if(session==null||expected==null||expected.isBlank()||!expected.equals(session.getAttribute("starhub.account.email")))return null;
        var actual=sessionOwner(request);
        if(actual==null||!backend.accountMatches(actual,expected)||!expected.equals(session.getAttribute("starhub.account.email")))return null;
        return actual;
    }
    private void requireSameCallback(HttpServletRequest request,GmailCredentialStore.Owner expected) throws Exception {
        if(!expected.equals(sessionOwner(request)))throw new IOException("Gmail account session changed");
    }
    private void requireSame(HttpServletRequest request,GmailCredentialStore.Owner expected) throws Exception {
        if(!expected.equals(owner(request)))throw new AccountChanged();
    }
    private boolean originOk(HttpServletRequest request){return "web".equals(request.getHeader("X-Starhub-Client"))&&
            DeploymentConfig.allowsOrigin(request.getHeader("Origin"))&&request.getContentType()!=null&&
            request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json");}

    @Override protected void doGet(HttpServletRequest request,HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control","no-store");String path=String.valueOf(request.getPathInfo());
        if("/callback".equals(path)){callback(request,response);return;}
        if(!"/status".equals(path)){fail(response,404,"接口不存在。");return;}
        try {
            var user=owner(request);if(user==null){fail(response,401,"请先登录。");return;}
            try{backend.config();byte[] vault=backend.vault();Arrays.fill(vault,(byte)0);}catch(Exception missing){
                json(response,200,ModelGateway.JSON.createObjectNode().put("available",false).put("state","NOT_CONFIGURED"));return;}
            var current=backend.read(user);var intent=backend.intent(user);requireSame(request,user);
            json(response,200,ModelGateway.JSON.createObjectNode().put("available",true)
                    .put("state",current!=null?current.status():intent!=null?intent.status():"DISCONNECTED"));
        } catch(AccountChanged changed){fail(response,401,"账户会话已更改或过期，请重新登录原账户。");}
        catch(Exception unavailable){fail(response,503,"Gmail 状态暂不可用，请稍后重试。");}
    }

    @Override protected void doPost(HttpServletRequest request,HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control","no-store");if(!originOk(request)){fail(response,403,"请求来源无效。");return;}
        byte[] raw=request.getInputStream().readNBytes(8193);if(raw.length>8192){fail(response,413,"请求过大。");return;}
        JsonNode input;try{input=ModelGateway.JSON.readTree(raw);}catch(Exception invalid){fail(response,400,"请求格式无效。");return;}
        if(input==null||!input.isObject()){fail(response,400,"请求格式无效。");return;}
        try {
            var user=owner(request);if(user==null){fail(response,401,"请先登录。");return;}
            switch(String.valueOf(request.getPathInfo())){
                case "/start" -> start(request,response,user);
                case "/search" -> search(request,response,user,input);
                case "/message" -> message(request,response,user,input);
                case "/disconnect" -> disconnect(request,response,user);
                case "/confirm-google-revoked" -> {if(!input.path("confirmed").isBoolean()||!input.path("confirmed").booleanValue())throw new IllegalArgumentException();backend.acknowledgeExternalRevocation(user);requireSame(request,user);json(response,200,ModelGateway.JSON.createObjectNode().put("state","DISCONNECTED"));}
                default -> fail(response,404,"接口不存在。");
            }
        } catch(AccountChanged changed){fail(response,401,"账户会话已更改或过期，请重新登录原账户。");}
        catch(IllegalArgumentException invalid){fail(response,400,"Gmail 请求无效。");}
        catch(Exception unavailable){fail(response,503,"Gmail 暂时不可用，请检查授权后重试。");}
    }

    private void start(HttpServletRequest request,HttpServletResponse response,GmailCredentialStore.Owner user) throws Exception {
        GmailSecurity.Config config=backend.config();byte[] vault=backend.vault();
        try{
            var current=backend.read(user);if(current!=null||backend.intent(user)!=null){fail(response,409,"请先断开现有 Gmail 连接或完成此前的断开操作。");return;}
            var challenge=GmailSecurity.challenge(clock.getAsLong());var sealed=GmailSecurity.seal(challenge.verifier(),vault,user.userId(),user.generation(),"pkce-verifier");
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(challenge.state().getBytes(StandardCharsets.US_ASCII));
            String operation=UUID.randomUUID().toString();backend.begin(user,operation,clock.getAsLong());
            requireSame(request,user);HttpSession session=request.getSession(false);if(session==null)throw new IOException("Session unavailable");
            synchronized(session){session.setAttribute(PENDING,new Pending(hash,sealed.nonce(),sealed.ciphertext(),challenge.createdMillis(),user.userId(),user.generation(),operation));}
            json(response,200,ModelGateway.JSON.createObjectNode().put("authorizationUrl",GmailSecurity.authorization(config,challenge).toString()));
        } finally {Arrays.fill(vault,(byte)0);}
    }

    private void callback(HttpServletRequest request,HttpServletResponse response) throws IOException {
        try {
            var user=sessionOwner(request);HttpSession session=request.getSession(false);String state=request.getParameter("state");
            if(user==null||session==null||state==null||!state.matches("[A-Za-z0-9_-]{43}")){callbackFailure(response);return;}
            GmailSecurity.Config config=backend.config();byte[] vault=backend.vault();
            try{
                Pending pending;byte[] actual=MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.US_ASCII));long now=clock.getAsLong();
                synchronized(session){Object value=session.getAttribute(PENDING);pending=value instanceof Pending?(Pending)value:null;
                    if(pending==null||pending.userId()!=user.userId()||!pending.generation().equals(user.generation())||now<pending.created()||now-pending.created()>GmailSecurity.STATE_LIFETIME_MILLIS||!MessageDigest.isEqual(actual,pending.stateHash())){
                        callbackFailure(response);return;}
                    session.removeAttribute(PENDING);
                }
                String base=config.redirectUri().resolve("/").toString();
                if(request.getParameter("error")!=null){backend.cancel(user,now);response.setStatus(303);response.setHeader("Location",base+"?gmail=denied");return;}
                String verifier=GmailSecurity.open(new GmailSecurity.Sealed(pending.nonce(),pending.verifier()),vault,user.userId(),user.generation(),"pkce-verifier");
                var challenge=new GmailSecurity.Challenge(state,verifier,"",pending.created());
                requireSameCallback(request,user);backend.exchange(user,pending.operation(),clock.getAsLong());
                String token;
                try{token=oauth.exchange(config,challenge,request.getParameter("code"));}
                catch(Exception unknown){try{backend.unknown(user,pending.operation(),clock.getAsLong());}catch(Exception ignored){}throw unknown;}
                String saved;
                try{saved=backend.accept(user,pending.operation(),token,vault,clock.getAsLong());}
                catch(Exception uncertain){
                    // The receipt is idempotent under the same operation ID. Never
                    // re-exchange the one-use Google authorization code.
                    try{saved=backend.accept(user,pending.operation(),token,vault,clock.getAsLong());}
                    catch(Exception stillUncertain){try{backend.unknown(user,pending.operation(),clock.getAsLong());}catch(Exception ignored){}throw stillUncertain;}
                }
                if(!"CONNECTED".equals(saved)){callbackFailure(response);return;}
                requireSameCallback(request,user);
                response.setStatus(303);response.setHeader("Location",base+"?gmail=connected");
            }finally{Arrays.fill(vault,(byte)0);}
        }catch(Exception unavailable){callbackFailure(response);}
    }

    private String access(GmailCredentialStore.Owner user,byte[] vault) throws Exception {
        var credential=backend.read(user);if(credential==null||!"CONNECTED".equals(credential.status()))throw new IOException("Gmail is not connected");
        return oauth.refresh(backend.config(),GmailSecurity.open(credential.credential(),vault,user.userId(),user.generation(),"refresh-token"));
    }
    private void search(HttpServletRequest request,HttpServletResponse response,GmailCredentialStore.Owner user,JsonNode input) throws Exception {
        if(!READS.tryAcquire()){fail(response,429,"Gmail 正忙，请稍后重试。");return;}
        byte[] vault=null;try{vault=backend.vault();String token=access(user,vault);
            int limit=input.path("limit").asInt(5);if(limit<1||limit>5)throw new IllegalArgumentException();
            var result=mailbox.search(token,input.path("query").asText(""),limit,input.path("pageToken").asText(""));requireSame(request,user);json(response,200,result);
        }finally{if(vault!=null)Arrays.fill(vault,(byte)0);READS.release();}
    }
    private void message(HttpServletRequest request,HttpServletResponse response,GmailCredentialStore.Owner user,JsonNode input) throws Exception {
        if(!READS.tryAcquire()){fail(response,429,"Gmail 正忙，请稍后重试。");return;}
        byte[] vault=null;try{vault=backend.vault();String token=access(user,vault);var result=mailbox.message(token,input.path("id").asText(""),true);requireSame(request,user);json(response,200,result);}
        finally{if(vault!=null)Arrays.fill(vault,(byte)0);READS.release();}
    }
    private void disconnect(HttpServletRequest request,HttpServletResponse response,GmailCredentialStore.Owner user) throws Exception {
        boolean cancelled=backend.cancel(user,clock.getAsLong());
        var pending=backend.beginDisconnect(user,clock.getAsLong());if(pending==null){requireSame(request,user);json(response,cancelled?200:202,ModelGateway.JSON.createObjectNode().put("state",cancelled?"DISCONNECTED":"UNKNOWN"));return;}
        byte[] vault=backend.vault();boolean confirmed=false;
        try{String token=GmailSecurity.open(pending.credential(),vault,user.userId(),user.generation(),"refresh-token");confirmed=oauth.revoke(token);}catch(IOException unknown){/* Keep disabled until revocation can be confirmed. */}
        finally{Arrays.fill(vault,(byte)0);}
        if(confirmed)confirmed=backend.finishDisconnect(user,pending.credential());requireSame(request,user);
        json(response,confirmed?200:202,ModelGateway.JSON.createObjectNode().put("state",confirmed?"DISCONNECTED":"REVOKING"));
    }
}
