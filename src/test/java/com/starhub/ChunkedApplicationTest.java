package com.starhub;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Full existing logical bounds on local SQL; this is not a cross-host capacity proof. */
class ChunkedApplicationTest {
    @Test void fullSixtyFourMiBBatchPublishesTogetherAndReadsEveryByteWithoutLegacyBlobWrites()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            List<FileStorage.Upload> uploads=new ArrayList<>();Random random=new Random(8723);long total=0;
            while(total<AtomicFileStorage.MAX_OPERATION_BYTES){int length=(int)Math.min(GeneratedImages.MAX_IMAGE,AtomicFileStorage.MAX_OPERATION_BYTES-total);byte[] bytes=new byte[length];random.nextBytes(bytes);uploads.add(new FileStorage.Upload("synthetic-"+uploads.size()+".txt","text/plain",bytes));total+=length;}
            String op=UUID.randomUUID().toString();var result=f.files.upload(f.ingress(),1,op,uploads);assertEquals("COMMITTED",result.path("state").asText());assertEquals(total,f.count("SELECT used_bytes FROM file_storage_users"));assertEquals(0,f.count("SELECT reserved_bytes FROM file_storage_users"));assertEquals(uploads.size(),f.count("SELECT COUNT(*) FROM library_files"));assertEquals(32,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertEquals(0,f.count("SELECT COUNT(*) FROM a.file_blobs"));assertEquals(0,f.count("SELECT COUNT(*) FROM b.file_blobs"));assertTrue(f.count("SELECT MAX(byte_count) FROM starhub_payload_chunks")<=ChunkedPayloadStore.CHUNK_BYTES);assertTrue(f.confirms.get()>32);
            for(int index=0;index<uploads.size();index++)assertArrayEquals(uploads.get(index).content,Base64.getDecoder().decode(f.files.read(1,result.path("ids").get(index).asText()).path("data").asText()));
            var other=new AtomicFileStorage(new AtomicDatabase(f.p,f::connection,f.authority),f.cluster());assertEquals(result,other.upload(f.ingress(),1,op,uploads));assertEquals(32,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
            try(Connection c=f.connection()){ChunkedRecoveryAudit.verify(c,f.files.payloads);}
        }
    }
    @Test void theAtomicScopeRejectsHiddenLargeNestedPayloadsAndRollsBackAlreadyInsertedChunks()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            String op=UUID.randomUUID().toString();byte[] bytes=new byte[4*1024*1024];new Random(19).nextBytes(bytes);var plan=f.files.payloads.plan(UUID.randomUUID().toString(),op,"TEST","synthetic-owner","synthetic-binding",bytes);
            try(AtomicDatabase.Scope scope=f.db.begin(op)){
                Connection c=f.db.current();ChunkedPayloadStore.reserve(c,plan);ChunkedPayloadStore.putChunk(c,plan,0,Arrays.copyOfRange(bytes,0,ChunkedPayloadStore.CHUNK_BYTES));
                assertThrows(SQLException.class,()->ChunkedPayloadStore.putChunk(c,plan,1,Arrays.copyOfRange(bytes,ChunkedPayloadStore.CHUNK_BYTES,bytes.length)));
            }
            assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_payload_manifests"));
        }
    }
    @Test void theOriginalTwelveMillionGuestCodeUnitLimitSurvivesChunkedCommitAndRewrite()throws Exception {
        try(var h=new GuestSessionServletTest.Harness()){
            String cookie="synthetic-max-guest",tab="b".repeat(32),first="c".repeat(32),second="d".repeat(32);
            String before="\""+("x".repeat(GuestSessionServlet.MAX_FIELD-2))+"\"",after="\""+("y".repeat(GuestSessionServlet.MAX_FIELD-2))+"\"";
            write(h,cookie,tab,first,1,before);assertEquals(GuestSessionServlet.MAX_FIELD,h.f.count("SELECT used_units FROM starhub_guest_budget"));
            write(h,cookie,tab,second,2,after);assertEquals(2L*GuestSessionServlet.MAX_FIELD,h.f.count("SELECT used_units FROM starhub_guest_budget"));assertEquals(1,h.f.count("SELECT COUNT(*) FROM starhub_guest_cleanup WHERE state='CANCELLING'"));
            var read=h.other().handle(cookie,null,input(tab,"get",""),2000);assertEquals("y".repeat(GuestSessionServlet.MAX_FIELD-2),read.body().path("history").asText());assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_guest_fields"));assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_guest_staging"));assertTrue(h.f.count("SELECT MAX(byte_count) FROM starhub_payload_chunks")<=200028);
            h.other().handle(cookie,read.identity(),input(tab,"renew","e".repeat(32)),2100);assertEquals(GuestSessionServlet.MAX_FIELD,h.f.count("SELECT used_units FROM starhub_guest_budget"));assertEquals(0,h.f.count("SELECT COUNT(*) FROM starhub_guest_cleanup WHERE state='CANCELLING'"));assertEquals(120,h.f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertTrue(h.f.count("SELECT MAX(OCTET_LENGTH(result_cipher)) FROM starhub_guest_operations")<16384);
            try(Connection c=h.f.connection()){ChunkedRecoveryAudit.verify(c,h.f.files.payloads);}
        }
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode input(String tab,String action,String tx){return ModelGateway.JSON.createObjectNode().put("tab",tab).put("action",action).put("tx",tx).put("field","history");}
    private static void write(GuestSessionServletTest.Harness h,String cookie,String tab,String tx,long version,String value)throws Exception {
        h.store.handle(cookie,null,input(tab,"begin",tx).put("version",version),1000);
        for(int offset=0;offset<value.length();offset+=100000)h.store.handle(cookie,null,input(tab,"chunk",tx).put("index",offset/100000).put("data",value.substring(offset,Math.min(value.length(),offset+100000))),1100);
        h.store.handle(cookie,null,input(tab,"commit",tx),1200);
    }
}
