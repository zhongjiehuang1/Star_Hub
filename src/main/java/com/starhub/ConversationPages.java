package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Bounded conversation replies with a stable owner/catalog snapshot across pages. */
final class ConversationPages {
    static final int PAGE_SIZE=4,MAX_CHATS=500,MAX_PAYLOAD_CHARACTERS=1_000_000,MAX_PAGE_BYTES=12*1024*1024;
    private ConversationPages(){}
    private record Item(String id,String title,long revision,String updated){}
    static ObjectNode load(Connection c,long user,String generation,JsonNode request)throws Exception{
        if(!request.path("paginationProtocol").isIntegralNumber()||request.path("paginationProtocol").asInt()!=1)
            throw new StorageFailure(428,"CHAT_CLIENT_UPGRADE_REQUIRED","请刷新页面后重新读取聊天记录。");
        JsonNode cursor=request.get("offset");int offset=0;
        if(cursor!=null){if(!cursor.isIntegralNumber()||!cursor.canConvertToInt())throw invalid();offset=cursor.asInt();}
        if(offset<0||offset>MAX_CHATS||generation==null||generation.isBlank())throw invalid();
        List<Item> catalog=new ArrayList<>();ArrayNode stamp=ModelGateway.JSON.createArrayNode();
        stamp.add(user);stamp.add(generation);
        try(PreparedStatement q=c.prepareStatement("SELECT chat_id,title,revision,updated_at FROM conversations WHERE user_id=? ORDER BY updated_at DESC,chat_id ASC LIMIT 501")){
            q.setLong(1,user);try(ResultSet r=q.executeQuery()){
                while(r.next()){
                    if(catalog.size()==MAX_CHATS)throw new StorageFailure(413,"CHAT_CATALOG_LIMIT","聊天目录超过限制，请联系维护人员；原记录已保留。");
                    Timestamp changed=r.getTimestamp(4);if(changed==null)throw new SQLException("Conversation timestamp missing");
                    Item item=new Item(r.getString(1),r.getString(2),r.getLong(3),changed.toInstant().toString());catalog.add(item);
                    stamp.addArray().add(item.id).add(item.title).add(item.revision).add(item.updated);
                }
            }
        }
        String snapshot=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ModelGateway.JSON.writeValueAsBytes(stamp)));
        JsonNode expected=request.get("snapshot");
        if((offset>0&&(expected==null||!expected.isTextual()))||(expected!=null&&(!expected.isTextual()||!snapshot.equals(expected.asText()))))
            throw new StorageFailure(409,"CHAT_CATALOG_CHANGED","聊天目录已在其他页面更改，请重新读取；当前页面内容仍保留。");
        if(offset>catalog.size())throw invalid();
        ObjectNode out=ModelGateway.JSON.createObjectNode().put("paginationProtocol",1).put("snapshot",snapshot).put("total",catalog.size());
        ArrayNode chats=out.putArray("chats");long bytes=0;int next=offset;
        while(next<catalog.size()&&chats.size()<PAGE_SIZE){
            Item item=catalog.get(next);String payload;
            try(PreparedStatement q=c.prepareStatement("SELECT payload FROM conversations WHERE user_id=? AND chat_id=? AND revision=?")){
                q.setLong(1,user);q.setString(2,item.id);q.setLong(3,item.revision);try(ResultSet r=q.executeQuery()){
                    if(!r.next())throw new StorageFailure(409,"CHAT_CATALOG_CHANGED","聊天记录已更改，请重新读取。");payload=r.getString(1);
                }
            }
            if(payload==null||payload.length()>MAX_PAYLOAD_CHARACTERS)
                throw new StorageFailure(413,"CHAT_PAYLOAD_LIMIT","已有聊天记录超过读取限制；原记录已保留，请联系维护人员。");
            int size=payload.getBytes(StandardCharsets.UTF_8).length;
            if(size>MAX_PAGE_BYTES)throw new StorageFailure(413,"CHAT_PAYLOAD_LIMIT","聊天记录超过读取限制；原记录已保留。");
            if(bytes+size>MAX_PAGE_BYTES)break;
            JsonNode data=ModelGateway.JSON.readTree(payload);if(data==null||!data.isObject())throw new SQLException("Conversation payload unavailable");
            ObjectNode value=chats.addObject().put("id",item.id).put("title",item.title).put("revision",item.revision);value.set("data",data);
            bytes+=size;next++;
        }
        if(next<catalog.size())out.put("nextOffset",next);else out.putNull("nextOffset");
        return out;
    }
    private static StorageFailure invalid(){return new StorageFailure(400,"INVALID_CHAT_PAGE","聊天分页请求无效。");}
}
