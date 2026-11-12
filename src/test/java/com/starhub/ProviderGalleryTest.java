package com.starhub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real local SQL and encryption, explicit synthetic authority/capacity; no paid API. */
class ProviderGalleryTest {
    private ProviderOperations.Owner account(ProviderOperationsTest.Harness h,String tab){return new ProviderOperations.Owner("account:1:"+h.f.generation+":"+tab,c->{});}
    private ObjectNode input(ProviderOperationsTest.Harness h,String op,int images){var body=h.input(op);body.putObject("parameters").put("n",images);return body;}
    private ObjectNode result(byte[]... images){var out=ModelGateway.JSON.createObjectNode().put("text","synthetic-images");var list=out.putArray("media");for(byte[] image:images)list.addObject().put("type","image").put("mime","image/png").put("imageBytes",image);return out;}
    private ProviderOperations.Ticket ticket(ProviderOperationsTest.Harness h,ProviderOperations operations,ProviderOperations.Owner owner,String op,int count)throws Exception {
        var request=input(h,op,count);assertEquals("CLAIMED",operations.claim(owner,op,"test:image",request).state());return operations.dispatch(owner,op,"test:image",request);
    }
    @Test void fourImagesAreEncryptedIndependentPayloadsAndReceiptsContainOnlySmallReferences()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            String op=UUID.randomUUID().toString();byte[][] images=new byte[4][];for(int i=0;i<4;i++){images[i]=new byte[GeneratedImages.MAX_IMAGE];new Random(i).nextBytes(images[i]);}
            var ticket=ticket(h,h.operations,h.owner,op,4);ticket.complete(200,result(images));var stored=h.other().status(h.owner,op);
            assertEquals("COMPLETED",stored.state());assertTrue(ModelGateway.JSON.writeValueAsBytes(stored.result()).length<4096);
            assertEquals(4,stored.result().path("media").size());assertEquals(4,h.f.count("SELECT COUNT(*) FROM starhub_payload_manifests WHERE kind='PROVIDER_IMAGE' AND state='PUBLISHED'"));
            for(int i=0;i<4;i++){var item=stored.result().path("media").get(i);assertFalse(item.has("imageBytes"));assertFalse(item.has("url"));String media=item.path("mediaRef").path("mediaId").asText();assertEquals(op,item.path("mediaRef").path("operationId").asText());assertArrayEquals(images[i],h.other().media(h.owner,op,media).bytes());
                assertEquals(404,assertThrows(StorageFailure.class,()->h.other().media(new ProviderOperations.Owner("guest:another:tab",c->{}),op,media)).status);
            }
            assertEquals(0,h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_payload_chunks WHERE byte_count>2097152"));
            try(Connection c=h.f.connection()){ChunkedRecoveryAudit.verify(c,new AtomicPayloads(h.f.db,h.f.p));}
        }
    }
    @Test void partialImageAcknowledgementLossDoesNotPublishAnyImageOrReleaseBudget()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            String op=UUID.randomUUID().toString();var base=h.f.authority;var fail=new AtomicBoolean(true);
            AtomicDatabase.Authority lost=new AtomicDatabase.Authority(){public ReplicaAuthority.Lease acquire()throws SQLException{return base.acquire();}public void unchanged(ReplicaAuthority.Lease l)throws SQLException{base.unchanged(l);}public void confirm(ReplicaAuthority.Lease l,String g,String id)throws SQLException{base.confirm(l,g,id);try{if(h.f.count("SELECT COUNT(*) FROM starhub_payload_chunks")==1&&fail.getAndSet(false))throw new SQLException("Synthetic image acknowledgment lost");}catch(SQLException e){throw e;}catch(Exception e){throw new SQLException("Fixture failed");}}};
            var ops=h.other(new AtomicDatabase(h.f.p,h.f::connection,lost));var ticket=ticket(h,ops,h.owner,op,2);var body=result(new byte[3*1024*1024],new byte[19]);
            assertThrows(SQLException.class,()->ticket.complete(200,body));ticket.unknown();var recovered=h.other().status(h.owner,op);assertEquals("UNKNOWN",recovered.state());assertNull(recovered.result());
            assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_payload_manifests WHERE state='PUBLISHED'"));assertTrue(h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals")>0);
            assertEquals(409,assertThrows(StorageFailure.class,()->h.other().media(h.owner,op,AtomicPayloads.id("provider-image-v1",op+":0"))).status);assertThrows(StorageFailure.class,()->h.other().dispatch(h.owner,op,"test:image",input(h,op,2)));
            try(Connection c=h.f.connection()){ChunkedRecoveryAudit.verify(c,new AtomicPayloads(h.f.db,h.f.p));}
        }
    }
    @Test void accountQuotaIsReservedBeforeDispatchAndRetriesNeverReserveTwice()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            long max=4L*GeneratedImages.MAX_IMAGE;try(Connection c=h.f.connection()){FileStorage.lockUser(c,1);try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_users SET used_bytes=? WHERE user_id=1")){q.setLong(1,WorkspaceServlet.QUOTA-max);q.executeUpdate();}}
            var owner=account(h,"a".repeat(32));String op=UUID.randomUUID().toString();var request=input(h,op,4);assertEquals("CLAIMED",h.operations.claim(owner,op,"test:image",request).state());
            assertEquals(max,h.f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));assertEquals("CLAIMED",h.other().claim(owner,op,"test:image",request).state());assertEquals(max,h.f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));
            String denied=UUID.randomUUID().toString();assertEquals(413,assertThrows(StorageFailure.class,()->h.other().claim(owner,denied,"test:image",input(h,denied,1))).status);assertEquals("ABSENT",h.other().status(owner,denied).state());assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_provider_operations"));
            var listed=h.f.files.list(1,ModelGateway.JSON.createObjectNode());assertEquals(WorkspaceServlet.QUOTA,listed.path("used").asLong());assertEquals(max,listed.path("pendingImageGenerations").get(0).path("reservedBytes").asLong());
            var ticket=h.operations.dispatch(owner,op,"test:image",request);ticket.unknown();assertEquals(max,h.f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));assertEquals("UNKNOWN",h.other().status(owner,op).state());
        }
    }
    @Test void accountFileCommitAfterRecipeCanBeRecoveredByAnotherInstanceWithoutAnotherExternalEffect()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            var owner=account(h,"a".repeat(32));String op=UUID.randomUUID().toString();var ticket=ticket(h,h.operations,owner,op,2);byte[] a={1,2,3},b={4,5,6,7};var uploads=List.of(new FileStorage.Upload("first.png","image/png",a),new FileStorage.Upload("second.png","image/png",b));ObjectNode body=result(a,b);String first;
            try(var context=ticket.enter()){
                var metadata=ModelGateway.JSON.createArrayNode();for(var upload:uploads){var item=upload.metadata();item.remove("id");metadata.add(item);}var reserved=h.f.files.reserve(1,op,metadata,2);first=reserved.path("ids").get(0).asText();
                for(int i=0;i<uploads.size();i++)((ObjectNode)body.path("media").get(i)).put("fileId",reserved.path("ids").get(i).asText());
                assertEquals(7,h.f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));assertEquals(0,h.f.count("SELECT logical_reserved FROM starhub_provider_operations"));ticket.prepareAccountImages(body);
                assertEquals("SENT",h.other().status(owner,op).state());assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_payload_manifests WHERE kind='PROVIDER_RESULT' AND state='READY'"));
                h.f.files.complete(h.f.ingress(),1,op,uploads);
                // Deliberately omit ticket.complete: this is the exact JVM-death
                // window after file COMMITTED, before the HTTP handler replied.
            }
            var recovered=h.other().status(owner,op);assertEquals("COMPLETED",recovered.state());assertEquals(first,recovered.result().path("media").get(0).path("fileId").asText());assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_payload_manifests WHERE kind='PROVIDER_IMAGE'"));
            assertEquals(7,h.f.count("SELECT used_bytes FROM file_storage_users WHERE user_id=1"));assertEquals(0,h.f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));assertEquals(0,h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));assertArrayEquals(a,h.f.files.readBinary(1,first).bytes());
            assertEquals(first,h.other().media(account(h,"b".repeat(32)),op,first).fileId());assertEquals(404,assertThrows(StorageFailure.class,()->h.other().media(new ProviderOperations.Owner("account:2:another-generation:"+"b".repeat(32),c->{}),op,first)).status);
            assertThrows(StorageFailure.class,()->h.other().dispatch(owner,op,"test:image",input(h,op,2)));
            try(Connection c=h.f.connection()){ChunkedRecoveryAudit.verify(c,new AtomicPayloads(h.f.db,h.f.p));}
        }
    }
    @Test void accountFailedGenerationCannotKeepUncountedFallbackImagesAndKnownFailureReleasesLogicalQuota()throws Exception {
        try(var h=new ProviderOperationsTest.Harness()){
            var owner=account(h,"a".repeat(32));String op=UUID.randomUUID().toString();var ticket=ticket(h,h.operations,owner,op,1);var failed=result(new byte[]{1,2,3}).put("imageSaveFailed",true);assertEquals("COMPLETED_NO_RESULT",ticket.complete(200,failed).state());
            assertEquals(0,h.f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_payload_manifests WHERE kind='PROVIDER_IMAGE'"));assertFalse(h.other().status(owner,op).result().has("media"));
            String invalid=UUID.randomUUID().toString();var validation=ticket(h,h.operations,owner,invalid,2);assertEquals(2L*GeneratedImages.MAX_IMAGE,h.f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));var known=validation.complete(400,ModelGateway.JSON.createObjectNode().put("error","Synthetic local validation rejection"));assertEquals("COMPLETED",known.state());assertEquals(400,known.httpStatus());assertEquals(0,h.f.count("SELECT reserved_bytes FROM file_storage_users WHERE user_id=1"));assertEquals(0,h.f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));
        }
    }
}
