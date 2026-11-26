package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** One shared JVM slot covers body expansion, SQL result loading and response
 * serialization. Small status and sign-out requests bypass it; a bounded login instead
 * requires its own one-request slot, never unbounded parallel password hashing.
 * The first 16 KiB are bounded even when a client lies about Content-Length. */
final class HeavyRequestBody implements AutoCloseable {
    static final int SMALL_LIMIT=16*1024;
    private static final Semaphore LOGIN_SLOT=new Semaphore(1);
    private final Runnable release;
    private final AtomicBoolean closed=new AtomicBoolean();
    final byte[] bytes;
    private HeavyRequestBody(byte[] bytes,Runnable release){this.bytes=bytes;this.release=release;}
    static HeavyRequestBody read(HttpServletRequest request,int limit,String endpoint)throws IOException {
        InputStream stream=request.getInputStream();byte[] first=stream.readNBytes(Math.min(limit,SMALL_LIMIT)+1);
        if(first.length>limit)throw tooLarge();
        boolean light=false,login=false;
        if(first.length<=SMALL_LIMIT){
            try(var parser=ModelGateway.JSON.getFactory().createParser(first)){
                parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode parsed=ModelGateway.JSON.readTree(parser);
                if(parser.nextToken()==null){light=smallRequest(endpoint,parsed);login=boundedLogin(endpoint,parsed);}
            }catch(com.fasterxml.jackson.core.JsonProcessingException malformed){/* Normal parser reports invalid JSON. */}
        }
        Runnable release=null;
        if(login){
            if(!LOGIN_SLOT.tryAcquire())throw new StorageFailure(429,"ACCOUNT_BUSY","服务器正在处理登录请求，请保留原操作编号后查询或重试。");
            release=LOGIN_SLOT::release;
        }else if(!light){
            ProviderAdmission.Lease lease=ProviderAdmission.tryEnter();
            if(lease==null)throw new StorageFailure(429,"MEMORY_BUSY","服务器正在处理大型请求，请保留原操作编号后查询或重试。");
            release=lease::close;
        }
        try{
            if(first.length<=SMALL_LIMIT)return new HeavyRequestBody(first,release);
            byte[] rest=stream.readNBytes(limit+1-first.length);
            if(first.length+rest.length>limit)throw tooLarge();
            byte[] body=new byte[first.length+rest.length];System.arraycopy(first,0,body,0,first.length);System.arraycopy(rest,0,body,first.length,rest.length);
            return new HeavyRequestBody(body,release);
        }catch(Throwable failure){if(release!=null)release.run();throw failure;}
    }
    private static boolean boundedLogin(String endpoint,JsonNode value){
        if(!"/api/account".equals(endpoint)||value==null||!value.isObject()||value.size()!=4)return false;
        Set<String> fields=Set.of("action","email","password","operationId");
        var names=value.fieldNames();while(names.hasNext()){String name=names.next();if(!fields.contains(name)||!value.path(name).isTextual())return false;}
        if(!"login".equals(value.path("action").asText())||value.path("email").asText().length()>254||value.path("password").asText().length()>256)return false;
        String operation=value.path("operationId").asText();
        try{return java.util.UUID.fromString(operation).toString().equals(operation);}catch(IllegalArgumentException invalid){return false;}
    }
    private static boolean smallRequest(String endpoint,JsonNode value){
        if(value==null||!value.isObject())return false;
        // Existing result queries, readFile, listChats, guest get/status and
        // provider-operation can all load large payloads: they never bypass.
        if ("/api/account".equals(endpoint) && "logout".equals(value.path("action").asText())) {
            JsonNode email = value.path("expectedEmail");
            return value.size() == 2 && email.isTextual() && email.textValue().length() <= 254;
        }
        Set<String> allowed;
        if("/api/workspace".equals(endpoint)&&"operationSummary".equals(value.path("action").asText()))allowed=Set.of("action","operationId","expectedEmail");
        else if("/api/account".equals(endpoint)&&"loginStatus".equals(value.path("action").asText()))allowed=Set.of("action","operationId","email");
        else return false;
        var names=value.fieldNames();while(names.hasNext())if(!allowed.contains(names.next()))return false;
        return true;
    }
    private static StorageFailure tooLarge(){return new StorageFailure(413,"REQUEST_TOO_LARGE","请求超过限制，请保留原操作编号。");}
    public void close(){if(release!=null&&closed.compareAndSet(false,true))release.run();}
}
