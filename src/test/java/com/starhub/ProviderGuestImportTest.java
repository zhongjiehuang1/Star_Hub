package com.starhub;

import jakarta.servlet.http.HttpSession;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Guest import is only a read from the current server-side guest identity. */
class ProviderGuestImportTest {
    @Test void currentAccountCanReadItsSameSessionGuestImageForExplicitImportButCannotChooseAnotherGuestOrTab()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            String guest=UUID.randomUUID().toString(),tab="a".repeat(32),op=UUID.randomUUID().toString();
            try(Connection c=h.f.connection();PreparedStatement q=c.prepareStatement("INSERT INTO starhub_guest_sessions(guest_id,cookie_hash,expires_at_millis) VALUES(?,?,?)")){q.setString(1,guest);q.setBytes(2,new byte[32]);q.setLong(3,System.currentTimeMillis()+600000);q.executeUpdate();}
            var previous=new ProviderOperations.Owner("guest:"+guest+":"+tab,c->{});var input=h.input(op);input.putObject("parameters").put("n",1);h.operations.claim(previous,op,"test:image",input);var ticket=h.operations.dispatch(previous,op,"test:image",input);
            var result=ModelGateway.JSON.createObjectNode();result.putArray("media").addObject().put("type","image").put("mime","image/png").put("imageBytes",new byte[]{1,2,3});var receipt=ticket.complete(200,result);String image=receipt.result().path("media").get(0).path("mediaRef").path("mediaId").asText();
            Map<String,Object> attributes=new HashMap<>();attributes.put("starhub.account.id",1L);attributes.put("starhub.account.email","user@example.test");attributes.put("starhub.account.generation",h.f.generation);attributes.put("starhub.account.hash",h.f.accountHash);attributes.put(GuestSessionServlet.IDENTITY,guest);
            HttpSession session=mock(HttpSession.class);when(session.getAttribute(anyString())).thenAnswer(invocation->attributes.get(invocation.getArgument(0)));
            var request=ModelGateway.JSON.createObjectNode().put("operationId",op).put("ownerTab",tab).put("expectedEmail","user@example.test").put("mediaId",image).put("guestImport",true);
            var imported=ProviderIdentity.guestImport(session,request,null);assertArrayEquals(new byte[]{1,2,3},h.other().media(imported,op,image).bytes());
            var normal=ProviderIdentity.from(session,request,null);assertEquals(404,assertThrows(StorageFailure.class,()->h.other().media(normal,op,image)).status);
            assertEquals(404,assertThrows(StorageFailure.class,()->h.other().media(ProviderIdentity.guestImport(session,request.deepCopy().put("ownerTab","b".repeat(32)),null),op,image)).status);
            attributes.put(GuestSessionServlet.IDENTITY,UUID.randomUUID().toString());assertEquals(401,assertThrows(StorageFailure.class,()->h.other().media(imported,op,image)).status);
            attributes.put(GuestSessionServlet.IDENTITY,guest);attributes.put("starhub.account.hash",new byte[]{9});assertEquals(401,assertThrows(StorageFailure.class,()->h.other().media(ProviderIdentity.guestImport(session,request,null),op,image)).status);
            attributes.put("starhub.account.hash",h.f.accountHash);try(Connection c=h.f.connection();PreparedStatement q=c.prepareStatement("UPDATE starhub_guest_sessions SET expires_at_millis=0 WHERE guest_id=?")){q.setString(1,guest);q.executeUpdate();}
            assertEquals("GUEST_IMPORT_EXPIRED",assertThrows(StorageFailure.class,()->h.other().media(ProviderIdentity.guestImport(session,request,null),op,image)).code);
            attributes.remove("starhub.account.id");assertEquals(401,assertThrows(StorageFailure.class,()->ProviderIdentity.guestImport(session,request,null)).status);
            assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_provider_operations"));assertEquals("COMPLETED",h.operations.status(previous,op).state());
        }
    }
}
