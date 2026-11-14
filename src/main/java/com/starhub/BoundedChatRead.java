package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.*;
import java.io.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Explicit opt-in only; result/absence queries, account migration and mutations never enter. */
final class BoundedChatRead {
    static final int OUTPUT_LIMIT=256*1024;
    private static final Semaphore SLOTS=new Semaphore(2,true);
    static boolean selected(HttpServletRequest req){return "chat-v1".equals(req.getHeader("X-Starhub-Bounded-Read"));}
    @FunctionalInterface interface FreshConfig {Properties load()throws Exception;}
    static void run(HttpServletRequest req,HttpServletResponse res,Properties config)throws IOException {run(req,res,()->config);}
    static void run(HttpServletRequest req,HttpServletResponse res,FreshConfig freshConfig)throws IOException {
        boolean admitted=false;
        try{
            if(!"POST".equals(req.getMethod())||!"/api/workspace".equals(req.getServletPath())||!"web".equals(req.getHeader("X-Starhub-Client"))||!DeploymentConfig.allowsOrigin(req.getHeader("Origin"))||req.getContentType()==null||!req.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json"))throw new StorageFailure(403,"INVALID_ORIGIN","请求来源无效。");
            admitted=SLOTS.tryAcquire();if(!admitted)throw new StorageFailure(429,"BOUNDED_READ_BUSY","正在读取其他页面，请稍后重试；当前内容已保留。");
            byte[] body=req.getInputStream().readNBytes(16*1024+1);if(body.length>16*1024)throw new StorageFailure(413,"BOUNDED_CHAT_FALLBACK","此页面需要标准读取。");
            JsonNode input=ModelGateway.JSON.readTree(body);Set<String> fields=Set.of("action","paginationProtocol","offset","snapshot","expectedEmail");
            if(input==null||!input.isObject()||!"listChats".equals(input.path("action").asText()))throw new StorageFailure(400,"INVALID_BOUNDED_READ","此读取协议不支持该操作。");
            var names=input.fieldNames();while(names.hasNext())if(!fields.contains(names.next()))throw new StorageFailure(400,"INVALID_BOUNDED_READ","此读取协议不支持该参数。");
            HttpSession session=req.getSession(false);
            byte[] bytes;String mode;
            try(BoundedReadScope scope=BoundedReadScope.open(freshConfig.load())){
                Actor owner=authenticate(scope.view,session,input);
                ObjectNode result=ConversationPages.loadBounded(scope.view,owner.id(),owner.generation(),input);
                bytes=encode(result);mode=scope.finish();
            }
                res.setStatus(200);res.setContentType("application/json;charset=UTF-8");res.setHeader("Cache-Control","no-store");res.setHeader("X-Four-Project-Durability",mode);res.setHeader("X-Starhub-Bounded-Read","chat-v1");res.getOutputStream().write(bytes);
        }catch(StorageFailure e){if(e.status==429)res.setHeader("Retry-After","1");AtomicHttp.failure(res,e.status,e.code,e.getMessage(),null);}
        catch(Exception e){AtomicHttp.failure(res,503,"CLUSTER_UNAVAILABLE","服务器故障，请等待恢复",null);}
        finally{if(admitted)SLOTS.release();}
    }
    record Actor(long id,String generation){}
    static Actor authenticate(Connection c,HttpSession session,JsonNode input)throws Exception {
        if(session==null||!(session.getAttribute("starhub.account.id")instanceof Long user)||!(session.getAttribute("starhub.account.hash")instanceof byte[] hash))throw new StorageFailure(401,"AUTHENTICATION_REQUIRED","请先登录。");
        String email=(String)session.getAttribute("starhub.account.email"),generation=(String)session.getAttribute("starhub.account.generation");
        if(!Objects.equals(email,input.path("expectedEmail").asText()))throw new StorageFailure(409,"ACCOUNT_CHANGED","账户已更改，请刷新后重试。");
        try(PreparedStatement q=c.prepareStatement("SELECT password_hash,email,generation FROM users WHERE id=? AND active=1")){
            q.setLong(1,user);try(ResultSet r=q.executeQuery()){if(!r.next()||!MessageDigest.isEqual(hash,r.getBytes(1))||!Objects.equals(email,r.getString(2))||!Objects.equals(generation,r.getString(3)))throw new StorageFailure(401,"AUTHENTICATION_REQUIRED","请先登录。");}
        }return new Actor(user,generation);
    }
    static byte[] encode(JsonNode value)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();OutputStream capped=new OutputStream(){
            private int count;
            public void write(int b)throws IOException{if(count>=OUTPUT_LIMIT)throw new IOException("Bounded chat output exceeds cap");bytes.write(b);count++;}
            public void write(byte[] b,int offset,int length)throws IOException{if(length>OUTPUT_LIMIT-count)throw new IOException("Bounded chat output exceeds cap");bytes.write(b,offset,length);count+=length;}
        };
        try{ModelGateway.JSON.writeValue(capped,value);}catch(IOException e){throw new StorageFailure(413,"BOUNDED_CHAT_FALLBACK","此页面需要标准读取。");}return bytes.toByteArray();
    }
}
