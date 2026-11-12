package com.starhub;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationPagesTest {
    private Connection database()throws Exception{
        Connection c=DriverManager.getConnection("jdbc:h2:mem:chat-pages-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        try(Statement q=c.createStatement()){q.execute("CREATE TABLE conversations(user_id BIGINT,chat_id VARCHAR(100),title VARCHAR(200),revision BIGINT,payload CLOB,updated_at TIMESTAMP(6),PRIMARY KEY(user_id,chat_id))");}
        return c;
    }
    private void insert(Connection c,long user,int index,String payload)throws Exception{
        try(PreparedStatement q=c.prepareStatement("INSERT INTO conversations VALUES(?,?,?,?,?,?)")){
            q.setLong(1,user);q.setString(2,"chat-"+String.format("%04d",index));q.setString(3,"Synthetic "+index);q.setLong(4,1);q.setString(5,payload);q.setTimestamp(6,Timestamp.valueOf("2026-11-11 12:00:00"));q.executeUpdate();
        }
    }
    private ObjectNode request(int offset,String snapshot){
        ObjectNode value=ModelGateway.JSON.createObjectNode().put("paginationProtocol",1).put("offset",offset);if(snapshot!=null)value.put("snapshot",snapshot);return value;
    }
    @Test void completeFiveHundredChatCatalogUsesBoundedOwnerPages()throws Exception{
        try(Connection c=database()){
            for(int i=0;i<500;i++)insert(c,1,i,"{\"messages\":[]}");insert(c,2,0,"{\"messages\":[{\"content\":\"another-owner\"}]}");
            int offset=0,count=0;String stamp=null;
            do{
                var page=ConversationPages.load(c,1,"generation-one",request(offset,stamp));assertEquals(500,page.path("total").asInt());assertTrue(page.path("chats").size()<=4);
                if(stamp!=null)assertEquals(stamp,page.path("snapshot").asText());stamp=page.path("snapshot").asText();count+=page.path("chats").size();
                for(var item:page.path("chats"))assertEquals(0,item.path("data").path("messages").size());
                if(page.path("nextOffset").isNull())break;offset=page.path("nextOffset").asInt();
            }while(true);
            assertEquals(500,count);
        }
    }
    @Test void concurrentCatalogMutationAndGenerationChangeRejectOldCursor()throws Exception{
        try(Connection c=database()){
            for(int i=0;i<5;i++)insert(c,1,i,"{\"messages\":[]}");var page=ConversationPages.load(c,1,"generation-one",request(0,null));String stamp=page.path("snapshot").asText();
            StorageFailure changedOwner=assertThrows(StorageFailure.class,()->ConversationPages.load(c,1,"generation-two",request(4,stamp)));assertEquals(409,changedOwner.status);
            try(Connection writer=DriverManager.getConnection(c.getMetaData().getURL());Statement q=writer.createStatement()){q.executeUpdate("UPDATE conversations SET revision=2 WHERE user_id=1 AND chat_id='chat-0004'");}
            StorageFailure changed=assertThrows(StorageFailure.class,()->ConversationPages.load(c,1,"generation-one",request(4,stamp)));assertEquals(409,changed.status);
        }
    }
    @Test void maximumLegalUnicodeChatsStayBelowBufferedReplyLimit()throws Exception{
        ObjectNode value=ModelGateway.JSON.createObjectNode().put("draft","汉".repeat(999900));value.putArray("messages");String payload=value.toString();assertTrue(payload.length()<=1_000_000);
        try(Connection c=database()){
            for(int i=0;i<5;i++)insert(c,1,i,payload);var page=ConversationPages.load(c,1,"g",request(0,null));
            assertEquals(4,page.path("chats").size());assertEquals(4,page.path("nextOffset").asInt());
            assertEquals(999900,page.path("chats").get(0).path("data").path("draft").asText().length());
            assertTrue(ModelGateway.JSON.writeValueAsBytes(page).length<ConversationPages.MAX_PAGE_BYTES+16384);
        }
    }
    @Test void oldClientInvalidCursorAndOversizedLegacyRowFailWithoutDeletingData()throws Exception{
        try(Connection c=database()){
            insert(c,1,0,"{\"messages\":[]}");assertEquals(428,assertThrows(StorageFailure.class,()->ConversationPages.load(c,1,"g",ModelGateway.JSON.createObjectNode())).status);
            assertEquals(400,assertThrows(StorageFailure.class,()->ConversationPages.load(c,1,"g",request(-1,null))).status);
            assertEquals(409,assertThrows(StorageFailure.class,()->ConversationPages.load(c,1,"g",request(1,null))).status);
            try(PreparedStatement q=c.prepareStatement("UPDATE conversations SET payload=? WHERE user_id=1")){q.setString(1,"x".repeat(1_000_001));q.executeUpdate();}
            assertEquals(413,assertThrows(StorageFailure.class,()->ConversationPages.load(c,1,"g",request(0,null))).status);
            try(Statement q=c.createStatement();ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM conversations")){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
        }
    }
}
