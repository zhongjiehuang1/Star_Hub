package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;

final class DesktopChats {
    static final String PURPOSE = "starhub/desktop-chat/v1";
    static final long LIFETIME = 90L * 86400;

    static ObjectNode save(Connection db, JsonNode grant, JsonNode input) throws Exception {
        long user = grant.path("user").asLong();
        try (var q = db.prepareStatement("SELECT generation,password_hash FROM users WHERE id=? AND active=1 FOR UPDATE")) {
            q.setLong(1,user);
            try (var r=q.executeQuery()) {
                if (!r.next() || !grant.path("generation").asText().equals(r.getString(1)) ||
                    !DesktopConnection.constantEquals(grant.path("password").asText(),DesktopConnection.digest(r.getBytes(2))))
                    throw new StorageFailure(401,"CONNECTION_EXPIRED","Reconnect this PC.");
            }
        }
        String id=input.path("id").asText(), title=input.path("title").asText();
        long expected=input.path("revision").asLong(-1);
        boolean delete=input.path("deleted").asBoolean(false);
        String payload=input.path("data").toString();
        if (!id.matches("win-[a-f0-9]{32}") || expected<0 || (!delete && (title.isBlank() || title.length()>200 ||
            !input.path("data").path("messages").isArray() || payload.length()>1_000_000)))
            throw new StorageFailure(400,"INVALID_CHAT","Invalid desktop conversation.");
        long revision=0;
        try(var q=db.prepareStatement("SELECT revision,title,payload FROM conversations WHERE user_id=? AND chat_id=? FOR UPDATE")){
            q.setLong(1,user);q.setString(2,id);
            try(var r=q.executeQuery()){if(r.next()){
                revision=r.getLong(1);
                if(!delete && title.equals(r.getString(2)) && input.path("data").equals(ModelGateway.JSON.readTree(r.getString(3))))return result(revision);
            }}
        }
        if(delete && revision==0)return result(0);
        if(expected!=revision)throw new StorageFailure(409,"CHAT_CHANGED","Conversation changed on the website.");
        if(delete){
            try(var q=db.prepareStatement("DELETE FROM conversations WHERE user_id=? AND chat_id=?")){
                q.setLong(1,user);q.setString(2,id);q.executeUpdate();
            }
            return result(0);
        }
        if(revision==0){
            try(var q=db.prepareStatement("SELECT COUNT(*) FROM conversations WHERE user_id=?")){
                q.setLong(1,user);try(var r=q.executeQuery()){r.next();if(r.getInt(1)>=500)throw new StorageFailure(413,"CHAT_LIMIT","Account conversation limit reached.");}
            }
        }
        try(var q=db.prepareStatement("INSERT INTO conversations(user_id,chat_id,title,payload,revision) VALUES(?,?,?,?,1) ON DUPLICATE KEY UPDATE title=VALUES(title),payload=VALUES(payload),revision=revision+1")){
            q.setLong(1,user);q.setString(2,id);q.setString(3,title);q.setString(4,payload);q.executeUpdate();
        }
        return result(revision+1);
    }

    private static ObjectNode result(long revision){return ModelGateway.JSON.createObjectNode().put("revision",revision);}
}
