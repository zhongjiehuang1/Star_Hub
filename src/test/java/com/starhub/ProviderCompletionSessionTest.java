package com.starhub;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A dispatched paid call can finish after the originating browser session changes. */
class ProviderCompletionSessionTest {
    @Test void expiredSessionBeforeFirstNetworkCallIsKnownUnsentAndNeverCallsProvider()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            AtomicBoolean expired=new AtomicBoolean();var owner=new ProviderOperations.Owner("test:owner",c->{},c->{},()->{if(expired.get())throw new StorageFailure(401,"SESSION_ENDED","ended");});
            String id=UUID.randomUUID().toString();var input=h.input(id);h.operations.claim(owner,id,"test:generate",input);var ticket=h.operations.dispatch(owner,id,"test:generate",input);expired.set(true);
            try(var context=ticket.enter()){
                var denial=assertThrows(ProviderOperations.NotDispatched.class,ProviderOperations::beforeExternal);assertEquals("SESSION_ENDED",denial.code);assertFalse(ticket.attempted());
            }
            assertEquals("COMPLETED",ticket.complete(401,ModelGateway.JSON.createObjectNode().put("code","SESSION_ENDED")).state());
            assertEquals(401,h.other().status(owner,id).httpStatus());
        }
    }
    @Test void aWorkflowAlreadySentMayFinishItsRemainingCallsAfterExpiry()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            AtomicBoolean expired=new AtomicBoolean();var owner=new ProviderOperations.Owner("test:owner",c->{},c->{},()->{if(expired.get())throw new StorageFailure(401,"SESSION_ENDED","ended");});
            String id=UUID.randomUUID().toString();var input=h.input(id);h.operations.claim(owner,id,"test:generate",input);var ticket=h.operations.dispatch(owner,id,"test:generate",input);
            try(var context=ticket.enter()){ProviderOperations.beforeExternal();expired.set(true);assertDoesNotThrow(ProviderOperations::beforeExternal);}
            assertEquals("COMPLETED",ticket.complete(200,ModelGateway.JSON.createObjectNode().put("text","completed")).state());
        }
    }
    @Test void completionSurvivesReloginButNewDispatchAndReadRequireCurrentSession() throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            var attrs=new HashMap<String,Object>();
            attrs.put("starhub.account.id",1L);attrs.put("starhub.account.email","user@example.test");
            attrs.put("starhub.account.generation",h.f.generation);attrs.put("starhub.account.hash",h.f.accountHash);
            var session=mock(jakarta.servlet.http.HttpSession.class);
            when(session.getAttribute(anyString())).thenAnswer(i->attrs.get(i.getArgument(0)));
            String id=UUID.randomUUID().toString();var input=h.input(id).put("ownerTab","a".repeat(32)).put("expectedEmail","user@example.test");
            var oldOwner=ProviderIdentity.from(session,input,null);
            h.operations.claim(oldOwner,id,"test:generate",input);var ticket=h.operations.dispatch(oldOwner,id,"test:generate",input);
            try(var context=ticket.enter()){ProviderOperations.beforeExternal();}
            attrs.remove("starhub.account.id");
            assertThrows(StorageFailure.class,()->h.operations.status(oldOwner,id));
            assertThrows(StorageFailure.class,ticket::verifyOwnerAndAuthority);
            byte[] newHash=new byte[h.f.accountHash.length];Arrays.fill(newHash,(byte)7);
            try(var c=h.f.connection();var q=c.prepareStatement("UPDATE users SET password_hash=? WHERE id=1")){q.setBytes(1,newHash);q.executeUpdate();}
            assertEquals("COMPLETED",ticket.complete(200,ModelGateway.JSON.createObjectNode().put("text","original result")).state());
            attrs.put("starhub.account.id",1L);attrs.put("starhub.account.hash",newHash);
            var reauthenticated=ProviderIdentity.from(session,input,null);
            assertEquals("original result",h.other().status(reauthenticated,id).result().path("text").asText());
        }
    }

    @Test void dispatchedImageCanFinishItsOwnLibraryWriteAfterSessionEnds() throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            var attrs=new HashMap<String,Object>();attrs.put("starhub.account.id",1L);attrs.put("starhub.account.email","user@example.test");
            attrs.put("starhub.account.generation",h.f.generation);attrs.put("starhub.account.hash",h.f.accountHash);
            var session=mock(jakarta.servlet.http.HttpSession.class);
            when(session.getAttribute(anyString())).thenAnswer(i->attrs.get(i.getArgument(0)));
            var req=h.f.ingress();when(req.getSession(false)).thenReturn(session);
            String id=UUID.randomUUID().toString();var input=h.input(id).put("ownerTab","a".repeat(32)).put("expectedEmail","user@example.test");input.putObject("parameters").put("n",1);
            var owner=ProviderIdentity.from(session,input,null);h.operations.claim(owner,id,"test:image",input);var ticket=h.operations.dispatch(owner,id,"test:image",input);
            byte[] image={1,2,3};var upload=new FileStorage.Upload("result.png","image/png",image);
            var metadata=ModelGateway.JSON.createArrayNode();var item=upload.metadata();item.remove("id");metadata.add(item);
            try(var context=ticket.enter()){
                ProviderOperations.beforeExternal();attrs.remove("starhub.account.id");
                try(var c=h.f.connection()){
                    assertThrows(java.sql.SQLException.class,()->ticket.verifyAccountCompletion(c,UUID.randomUUID().toString(),1,h.f.generation));
                    assertThrows(java.sql.SQLException.class,()->ticket.verifyAccountCompletion(c,id,2,h.f.generation));
                }
                var reserved=h.f.files.reserve(1,id,metadata,2);String file=reserved.path("ids").get(0).asText();
                var result=ModelGateway.JSON.createObjectNode();result.putArray("media").addObject().put("type","image").put("mime","image/png").put("fileId",file);
                ticket.prepareAccountImages(result);h.f.files.complete(req,1,id,List.of(upload));
                assertEquals("COMPLETED",ticket.complete(200,result).state());
                assertArrayEquals(image,h.f.files.readBinary(1,file).bytes());
            }
            attrs.put("starhub.account.id",1L);assertEquals("COMPLETED",h.other().status(ProviderIdentity.from(session,input,null),id).state());
        }
    }
}
