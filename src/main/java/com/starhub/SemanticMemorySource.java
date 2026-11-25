package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;

/** Confirms that extraction is tied to a saved user message, not model/tool output. */
final class SemanticMemorySource {
    private SemanticMemorySource() {}
    static void require(Connection c,long user,String generation,JsonNode request)throws Exception{
        String chat=request.path("sourceChatId").asText(),text=request.path("text").asText();
        JsonNode index=request.path("sourceMessageIndex");
        if(!chat.matches("[A-Za-z0-9_-]{1,100}")||!index.isIntegralNumber()||!index.canConvertToInt()||index.intValue()<0||index.intValue()>=1000||SemanticMemoryExtractor.eligibleInput(text)==null)throw invalid();
        // Owner generation is checked and locked with the memory revision by
        // the caller; conversations are keyed by user id.
        try(PreparedStatement q=c.prepareStatement("SELECT payload FROM conversations WHERE user_id=? AND chat_id=?")){
            q.setLong(1,user);q.setString(2,chat);try(ResultSet r=q.executeQuery()){
                if(!r.next())throw invalid();String payload=r.getString(1);if(payload==null||payload.length()>1_000_000)throw invalid();
                JsonNode message=ModelGateway.JSON.readTree(payload).path("messages").path(index.intValue());
                if(!"user".equals(message.path("role").asText())||!"typed".equals(message.path("memorySource").asText())||!text.equals(message.path("content").asText())||message.path("fileIds").size()>0||message.path("imageCount").asInt()!=0)throw invalid();
            }
        }
    }
    private static StorageFailure invalid(){return new StorageFailure(400,"MEMORY_SOURCE_INVALID","此内容不适合自动记忆。 ");}
}
