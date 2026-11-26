package com.starhub;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopChatsTest {
    @Test void emptyChatThenQuestionThenReplyAreReadableByOnlyTheirOwner() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL");
             var sql = db.createStatement()) {
            sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,active INT,generation VARCHAR,password_hash VARBINARY)");
            sql.execute("INSERT INTO users VALUES(1,1,'first',X'01'),(2,1,'second',X'02')");
            sql.execute("CREATE TABLE conversations(user_id BIGINT,chat_id VARCHAR,title VARCHAR,payload VARCHAR,revision BIGINT,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(user_id,chat_id))");
            var grant = ModelGateway.JSON.createObjectNode().put("user",1).put("generation","first")
                .put("password",DesktopConnection.digest(new byte[]{1}));
            var chat = ModelGateway.JSON.createObjectNode().put("id","win-"+"a".repeat(32)).put("title","New conversation").put("revision",0);
            var messages = chat.putObject("data").putArray("messages");
            var read = ModelGateway.JSON.createObjectNode().put("paginationProtocol",1);
            assertEquals(1,DesktopChats.save(db,grant,chat).path("revision").asInt());
            assertEquals(1,DesktopChats.save(db,grant,chat).path("revision").asInt());
            assertTrue(ConversationPages.load(db,1,"first",read).at("/chats/0/data/messages").isEmpty());
            chat.put("revision",1).put("title","3+4?");
            messages.addObject().put("role","user").put("content","3+4?");
            assertEquals(2,DesktopChats.save(db,grant,chat).path("revision").asInt());
            messages.addObject().put("role","assistant").put("content","7");
            assertEquals(409,assertThrows(StorageFailure.class,()->DesktopChats.save(db,grant,chat)).status);
            chat.put("revision",2);
            assertEquals(3,DesktopChats.save(db,grant,chat).path("revision").asInt());
            assertEquals(messages,ConversationPages.load(db,1,"first",read).at("/chats/0/data/messages"));
            assertTrue(ConversationPages.load(db,2,"second",read).path("chats").isEmpty());
            assertEquals(401,assertThrows(StorageFailure.class,()->DesktopChats.save(db,grant.deepCopy().put("user",2),chat)).status);
            chat.put("revision",3).put("deleted",true);
            assertEquals(0,DesktopChats.save(db,grant,chat).path("revision").asInt());
            assertTrue(ConversationPages.load(db,1,"first",read).path("chats").isEmpty());
        }
    }
}
