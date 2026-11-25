package com.starhub;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.HttpSession;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Server-side import uses the real bounded file transaction path; no vendor calls. */
class ProviderImageImportsTest {
    private record Source(String operation,String media,ProviderOperations.Owner guest){}
    private ProviderOperations.Owner account(ProviderOperationsTest.Harness h){return new ProviderOperations.Owner("account:1:"+h.f.generation+":"+"b".repeat(32),c->{});}
    private Source source(ProviderOperationsTest.Harness h,byte[] bytes)throws Exception {
        String op=UUID.randomUUID().toString();var guest=new ProviderOperations.Owner("guest:"+UUID.randomUUID()+":"+"a".repeat(32),c->{});var input=h.input(op);input.putObject("parameters").put("n",1);h.operations.claim(guest,op,"test:image",input);var ticket=h.operations.dispatch(guest,op,"test:image",input);
        var result=ModelGateway.JSON.createObjectNode();result.putArray("media").addObject().put("type","image").put("mime","image/png").put("imageBytes",bytes);var receipt=ticket.complete(200,result);return new Source(op,receipt.result().path("media").get(0).path("mediaRef").path("mediaId").asText(),guest);
    }
    private ProviderImageImports bridge(ProviderOperationsTest.Harness h,ProviderOperations operations){return new ProviderImageImports(h.f.files,operations,(user,op,files)->h.f.files.upload(h.f.ingress(),user,op,files));}
    @Test void twelveMiBImportAfterLostFileCommitAcknowledgementReplaysAcrossInstancesEvenIfGuestExpired()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            byte[] bytes=new byte[GeneratedImages.MAX_IMAGE];new Random(52).nextBytes(bytes);var source=source(h,bytes);String id=UUID.randomUUID().toString();var account=account(h);var importer=bridge(h,h.operations);
            assertEquals("ABSENT",importer.execute(1,id,source.operation,source.media,true,account,()->{throw new AssertionError("Status must not read a guest");}).body().path("state").asText());
            h.f.losePublishedCommit.set(true);assertThrows(SQLException.class,()->importer.execute(1,id,source.operation,source.media,false,account,()->source.guest));
            assertEquals(1,h.f.count("SELECT COUNT(*) FROM library_files"));assertEquals(bytes.length,h.f.count("SELECT used_bytes FROM file_storage_users WHERE user_id=1"));assertEquals(0,h.f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));
            var replay=bridge(h,h.other()).execute(1,id,source.operation,source.media,false,account,()->{throw new StorageFailure(401,"GUEST_IMPORT_EXPIRED","Synthetic old guest expired");});
            assertEquals("COMMITTED",replay.body().path("state").asText());assertEquals("dual",replay.durability());assertEquals(id,replay.body().path("importOperationId").asText());assertArrayEquals(bytes,h.f.files.readBinary(1,replay.body().path("fileId").asText()).bytes());assertEquals(1,h.f.count("SELECT COUNT(*) FROM library_files"));
            assertEquals(409,assertThrows(StorageFailure.class,()->bridge(h,h.other()).execute(1,id,UUID.randomUUID().toString(),source.media,false,account,()->{throw new AssertionError("Changed source must reject before lookup");})).status);
            assertEquals(401,assertThrows(StorageFailure.class,()->bridge(h,h.other()).execute(1,id,source.operation,source.media,false,new ProviderOperations.Owner("account:2:another:tab",c->{}),()->source.guest)).status);
            Map<String,Object> attributes=new HashMap<>();attributes.put("starhub.account.id",1L);attributes.put("starhub.account.email","user@example.test");attributes.put("starhub.account.generation",h.f.generation);attributes.put("starhub.account.hash",h.f.accountHash);
            HttpSession fresh=mock(HttpSession.class);when(fresh.getAttribute(anyString())).thenAnswer(invocation->attributes.get(invocation.getArgument(0)));
            var readRequest=ModelGateway.JSON.createObjectNode().put("ownerTab","c".repeat(32)).put("expectedEmail","user@example.test").put("operationId",source.operation);
            var newLogin=ProviderIdentity.from(fresh,readRequest,null);var imported=bridge(h,h.other()).file(1,id,source.operation,replay.body().path("fileId").asText(),newLogin);assertEquals(replay.body().path("fileId").asText(),imported.id());assertEquals("image/png",imported.mime());
            verify(fresh,atLeastOnce()).getAttribute("starhub.account.id");assertNull(fresh.getAttribute(GuestSessionServlet.IDENTITY));
            assertEquals(404,assertThrows(StorageFailure.class,()->bridge(h,h.other()).file(1,id,UUID.randomUUID().toString(),imported.id(),newLogin)).status);assertEquals(404,assertThrows(StorageFailure.class,()->bridge(h,h.other()).file(1,id,source.operation,UUID.randomUUID().toString(),newLogin)).status);
            try(Connection c=h.f.connection();PreparedStatement q=c.prepareStatement("UPDATE users SET password_hash=? WHERE id=1")){q.setBytes(1,new byte[]{9});q.executeUpdate();}assertEquals(401,assertThrows(StorageFailure.class,()->bridge(h,h.other()).file(1,id,source.operation,imported.id(),newLogin)).status);
            assertEquals("COMPLETED",h.operations.status(source.guest,source.operation).state()); // source is preserved.
            assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_payload_chunks WHERE byte_count>2097152"));
        }
    }
    @Test void ordinaryUploadsCannotForgeImportProvenanceOrCopyAnAuthenticatedNameToAnotherOperation()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            var actor=account(h);var importer=bridge(h,h.operations);String source=UUID.randomUUID().toString(),media=UUID.randomUUID().toString(),forged=UUID.randomUUID().toString();String visible="Star-Hub-import-"+source+"-"+media;
            var ordinary=h.f.files.upload(h.f.ingress(),1,forged,List.of(new FileStorage.Upload(visible,"image/png",new byte[]{7,8,9})));
            assertEquals(409,assertThrows(StorageFailure.class,()->importer.execute(1,forged,source,media,false,actor,()->{throw new AssertionError("A visible filename cannot establish a guest source");})).status);
            assertEquals(404,assertThrows(StorageFailure.class,()->importer.file(1,forged,source,ordinary.path("id").asText(),actor)).status);
            var legitimate=source(h,new byte[]{1,2,3});String original=UUID.randomUUID().toString();var imported=importer.execute(1,original,legitimate.operation,legitimate.media,false,actor,()->legitimate.guest);String proofName=h.f.files.readBinary(1,imported.body().path("fileId").asText()).name();assertTrue(proofName.length()<255);
            String copied=UUID.randomUUID().toString();h.f.files.upload(h.f.ingress(),1,copied,List.of(new FileStorage.Upload(proofName,"image/png",new byte[]{1,2,3})));
            assertEquals(409,assertThrows(StorageFailure.class,()->importer.execute(1,copied,legitimate.operation,legitimate.media,true,actor,()->{throw new AssertionError("An observed proof is bound to its original destination operation");})).status);
        }
    }
    @Test void quotaRefusalDoesNotCreateImportAndUnfinishedExpiredSourceDoesNotReleaseItsReservation()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            var source=source(h,new byte[]{1,2,3});var importer=bridge(h,h.operations);var account=account(h);String denied=UUID.randomUUID().toString();
            try(Connection c=h.f.connection()){FileStorage.lockUser(c,1);c.createStatement().executeUpdate("UPDATE file_storage_users SET used_bytes=2000000000 WHERE user_id=1");}
            assertEquals(413,assertThrows(StorageFailure.class,()->importer.execute(1,denied,source.operation,source.media,false,account,()->source.guest)).status);assertEquals(0,h.f.count("SELECT COUNT(*) FROM file_storage_operations"));
            try(Connection c=h.f.connection()){c.createStatement().executeUpdate("UPDATE file_storage_users SET used_bytes=0 WHERE user_id=1");}
            String pending=UUID.randomUUID().toString();String name=importer.execute(1,pending,source.operation,source.media,true,account,()->{throw new AssertionError("Status does not read guest");}).destinationName();var file=new FileStorage.Upload(name,"image/png",new byte[]{1,2,3});var metadata=ModelGateway.JSON.createArrayNode();var item=file.metadata();item.remove("id");metadata.add(item);h.f.files.reserve(1,pending,metadata,1);
            assertEquals("GUEST_IMPORT_EXPIRED",assertThrows(StorageFailure.class,()->importer.execute(1,pending,source.operation,source.media,false,account,()->{throw new StorageFailure(401,"GUEST_IMPORT_EXPIRED","Synthetic expiry");})).code);
            assertEquals(3,h.f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));assertEquals("RESERVED",importer.execute(1,pending,source.operation,source.media,true,account,()->{throw new AssertionError("Status must not require expired source");}).body().path("state").asText());assertEquals(0,h.f.count("SELECT COUNT(*) FROM library_files"));
        }
    }
}
