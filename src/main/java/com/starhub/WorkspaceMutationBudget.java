package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.*;

/** Payload units for the per-volume physical model, not an extra disk floor.
 * FULL row images include both the old and new conversation values. */
final class WorkspaceMutationBudget {
    static final long MAX_CHAT_UTF8_BYTES=3_000_000L,MAX_TITLE_UTF8_BYTES=800L;
    static long estimate(Connection c,long user,String endpoint,JsonNode input)throws Exception {
        long bytes=AtomicHttp.MAX_MUTATION_RECEIPT_ROW_BYTES;
        if(!endpoint.equals("/api/workspace"))return bytes;
        String action=input.path("action").asText();if(!action.equals("saveChat")&&!action.equals("deleteChat"))return bytes;
        try(PreparedStatement q=c.prepareStatement("SELECT OCTET_LENGTH(payload),OCTET_LENGTH(title) FROM conversations WHERE user_id=? AND chat_id=?")){
            q.setLong(1,user);q.setString(2,input.path("id").asText());try(ResultSet r=q.executeQuery()){
                if(r.next()){long payload=r.getLong(1),title=r.getLong(2);if(payload<0||payload>MAX_CHAT_UTF8_BYTES||title<0||title>MAX_TITLE_UTF8_BYTES)throw new StorageFailure(503,"LEGACY_CAPACITY_MIGRATION_REQUIRED","历史聊天超过当前容量模型边界；原记录保留，需要先受控迁移。");bytes=Math.addExact(bytes,Math.addExact(payload,title));}
            }
        }
        if(action.equals("saveChat")){
            String payload=input.path("data").toString(),title=input.path("title").asText();
            // Invalid public requests still get the servlet's validation error;
            // no large allocation is authorized before that validation.
            if(payload.length()>1_000_000||title.length()>200)throw new StorageFailure(400,"INVALID_CHAT","聊天记录不符合限制。");
            bytes=Math.addExact(bytes,Math.addExact(payload.getBytes(StandardCharsets.UTF_8).length,title.getBytes(StandardCharsets.UTF_8).length));
        }
        if(bytes>AtomicDatabase.MAX_COMPETING_TRANSACTION_BYTES)throw new StorageFailure(503,"CAPACITY_MODEL_BOUND_EXCEEDED","当前操作超过已声明的事务容量边界；原数据保留。");return bytes;
    }
    private WorkspaceMutationBudget(){}
}
