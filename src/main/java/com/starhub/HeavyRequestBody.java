package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.Set;

/** One shared JVM slot covers body expansion, SQL result loading and response
 * serialization. Only an explicitly small, status-only protocol may bypass it.
 * The first 16 KiB are bounded even when a client lies about Content-Length. */
final class HeavyRequestBody implements AutoCloseable {
    static final int SMALL_LIMIT=16*1024;
    private final ProviderAdmission.Lease lease;
    final byte[] bytes;
    private HeavyRequestBody(byte[] bytes,ProviderAdmission.Lease lease){this.bytes=bytes;this.lease=lease;}
    static HeavyRequestBody read(HttpServletRequest request,int limit,String endpoint)throws IOException {
        InputStream stream=request.getInputStream();byte[] first=stream.readNBytes(Math.min(limit,SMALL_LIMIT)+1);
        if(first.length>limit)throw tooLarge();
        boolean light=false;
        if(first.length<=SMALL_LIMIT){
            try{light=smallStatus(endpoint,ModelGateway.JSON.readTree(first));}catch(com.fasterxml.jackson.core.JsonProcessingException malformed){/* Normal parser reports invalid JSON. */}
        }
        ProviderAdmission.Lease lease=light?null:ProviderAdmission.tryEnter();
        if(!light&&lease==null)throw new StorageFailure(429,"MEMORY_BUSY","服务器正在处理大型请求，请保留原操作编号后查询或重试。");
        try{
            if(first.length<=SMALL_LIMIT)return new HeavyRequestBody(first,lease);
            byte[] rest=stream.readNBytes(limit+1-first.length);
            if(first.length+rest.length>limit)throw tooLarge();
            byte[] body=new byte[first.length+rest.length];System.arraycopy(first,0,body,0,first.length);System.arraycopy(rest,0,body,first.length,rest.length);
            return new HeavyRequestBody(body,lease);
        }catch(Throwable failure){if(lease!=null)lease.close();throw failure;}
    }
    private static boolean smallStatus(String endpoint,JsonNode value){
        if(value==null||!value.isObject())return false;
        // Existing result queries, readFile, listChats, guest get/status and
        // provider-operation can all load large payloads: they never bypass.
        Set<String> allowed;
        if("/api/workspace".equals(endpoint)&&"operationSummary".equals(value.path("action").asText()))allowed=Set.of("action","operationId","expectedEmail");
        else if("/api/account".equals(endpoint)&&"loginStatus".equals(value.path("action").asText()))allowed=Set.of("action","operationId","email");
        else return false;
        var names=value.fieldNames();while(names.hasNext())if(!allowed.contains(names.next()))return false;
        return true;
    }
    private static StorageFailure tooLarge(){return new StorageFailure(413,"REQUEST_TOO_LARGE","请求超过限制，请保留原操作编号。");}
    public void close(){if(lease!=null)lease.close();}
}
