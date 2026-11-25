package com.starhub;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL/independent connections; not a MySQL GR or cloud-fault substitute. */
class ChunkedPayloadStoreTest {
    @FunctionalInterface interface Work<T>{T run(Connection c)throws Exception;}
    static final class Fixture {
        final String url="jdbc:h2:mem:payload_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Fixture()throws Exception{
            try(Connection c=open();Statement q=c.createStatement()){
                String ddl=Files.readString(Path.of("deploy/migrations/012_chunked_payload_store.sql")).replaceAll("(?m)^--.*$","");
                for(String sql:ddl.split(";"))if(!sql.isBlank())q.execute(sql);
                q.execute("CREATE TABLE retained_budget(id INT PRIMARY KEY,bytes BIGINT NOT NULL)");q.execute("INSERT INTO retained_budget VALUES(1,100)");
                q.execute("CREATE TABLE business_reference(id INT PRIMARY KEY,payload_id CHAR(36))");
            }
        }
        Connection open()throws SQLException{return DriverManager.getConnection(url);}
        <T>T tx(Work<T> work)throws Exception{try(Connection c=open()){c.setAutoCommit(false);try{T result=work.run(c);c.commit();return result;}catch(Exception|AssertionError failed){c.rollback();throw failed;}}}
        void execute(String sql)throws Exception{tx(c->{c.createStatement().executeUpdate(sql);return null;});}
        long count(String sql)throws Exception{try(Connection c=open();Statement q=c.createStatement();ResultSet r=q.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
        String state(ChunkedPayloadStore.Plan p)throws Exception{return tx(c->ChunkedPayloadStore.state(c,p));}
    }
    static byte[] hash(byte[] bytes)throws Exception{return MessageDigest.getInstance("SHA-256").digest(bytes);}
    static ChunkedPayloadStore.Plan plan(byte[] body)throws Exception{return new ChunkedPayloadStore.Plan(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"FILE",hash(new byte[]{1}),hash(new byte[]{2}),body.length,ChunkedPayloadStore.chunks(body.length),hash(body));}
    static byte[] chunk(byte[] body,int index){return Arrays.copyOfRange(body,index*ChunkedPayloadStore.CHUNK_BYTES,Math.min(body.length,(index+1)*ChunkedPayloadStore.CHUNK_BYTES));}
    static byte[] body(int count){byte[] value=new byte[count];new Random(17).nextBytes(value);return value;}
    static void reserve(Fixture f,ChunkedPayloadStore.Plan p)throws Exception{f.tx(c->{ChunkedPayloadStore.reserve(c,p);return null;});}
    static void put(Fixture f,ChunkedPayloadStore.Plan p,int index,byte[] bytes)throws Exception{f.tx(c->{ChunkedPayloadStore.putChunk(c,p,index,bytes);return null;});}

    @Test void independentConnectionsResumeOutOfOrderChunksThenPublishOnlyVerifiedCompleteManifest()throws Exception{
        Fixture f=new Fixture();byte[] body=body(ChunkedPayloadStore.CHUNK_BYTES*2+37);var p=plan(body);reserve(f,p);
        put(f,p,2,chunk(body,2));put(f,p,0,chunk(body,0));
        assertThrows(SQLException.class,()->f.tx(c->{ChunkedPayloadStore.seal(c,p);return null;}));assertEquals("STAGING",f.state(p));
        assertThrows(StorageFailure.class,()->f.tx(c->ChunkedPayloadStore.readBytes(c,p,body.length)));
        put(f,p,1,chunk(body,1));f.tx(c->{ChunkedPayloadStore.seal(c,p);return null;});assertEquals("READY",f.state(p));
        assertArrayEquals(body,f.tx(c->ChunkedPayloadStore.readBytes(c,p,body.length)));
        f.tx(c->{ChunkedPayloadStore.publish(c,p);return null;});assertEquals("PUBLISHED",f.state(p));
        ByteArrayOutputStream stream=new ByteArrayOutputStream();f.tx(c->{ChunkedPayloadStore.read(c,p,stream);return null;});assertArrayEquals(body,stream.toByteArray());
        assertThrows(StorageFailure.class,()->f.tx(c->ChunkedPayloadStore.readBytes(c,p,body.length-1)));
        assertThrows(StorageFailure.class,()->f.tx(c->{ChunkedPayloadStore.cancel(c,p);return null;}));
        assertEquals(3,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
    }
    @Test void duplicateChunkIsImmutableAcrossUnknownCommitAndChangedBindingsNeverOverwrite()throws Exception{
        Fixture f=new Fixture();byte[] bytes=body(1024);var p=plan(bytes);reserve(f,p);
        try(Connection c=f.open()){c.setAutoCommit(false);ChunkedPayloadStore.putChunk(c,p,0,bytes);c.commit();/* response lost after actual DB commit */}
        put(f,p,0,bytes);reserve(f,p);assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
        byte[] changed=bytes.clone();changed[0]^=1;
        assertEquals(409,assertThrows(StorageFailure.class,()->put(f,p,0,changed)).status);
        assertEquals(400,assertThrows(StorageFailure.class,()->put(f,p,1,bytes)).status);
        assertEquals(400,assertThrows(StorageFailure.class,()->put(f,p,0,new byte[1])).status);
        var wrongOwner=new ChunkedPayloadStore.Plan(p.id(),p.operation(),p.kind(),hash(new byte[]{8}),p.requestHash(),p.byteCount(),p.chunkCount(),p.sha256());
        var wrongRequest=new ChunkedPayloadStore.Plan(p.id(),p.operation(),p.kind(),p.ownerHash(),hash(new byte[]{9}),p.byteCount(),p.chunkCount(),p.sha256());
        var wrongOperation=new ChunkedPayloadStore.Plan(p.id(),UUID.randomUUID().toString(),p.kind(),p.ownerHash(),p.requestHash(),p.byteCount(),p.chunkCount(),p.sha256());
        for(var wrong:List.of(wrongOwner,wrongRequest,wrongOperation))assertEquals(409,assertThrows(StorageFailure.class,()->reserve(f,wrong)).status);
        f.tx(c->{ChunkedPayloadStore.seal(c,p);ChunkedPayloadStore.publish(c,p);return null;});
        put(f,p,0,bytes);assertArrayEquals(bytes,f.tx(c->ChunkedPayloadStore.readBytes(c,p,1024)));
    }
    @Test void rollbackLeavesNoChunkAndPendingCancellationRetainsBytesAndBudgetUntilConfirmedCleanup()throws Exception{
        Fixture f=new Fixture();byte[] bytes=body(ChunkedPayloadStore.CHUNK_BYTES+9);var p=plan(bytes);reserve(f,p);
        assertThrows(IOException.class,()->f.tx(c->{ChunkedPayloadStore.putChunk(c,p,0,chunk(bytes,0));throw new IOException("synthetic interruption before commit");}));
        assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));put(f,p,0,chunk(bytes,0));put(f,p,1,chunk(bytes,1));
        f.tx(c->{ChunkedPayloadStore.cancel(c,p);return null;});assertEquals("CANCELLING",f.state(p));
        assertEquals(2,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertEquals(100,f.count("SELECT bytes FROM retained_budget WHERE id=1"));
        assertThrows(StorageFailure.class,()->f.tx(c->{ChunkedPayloadStore.finishCancel(c,p);return null;}));
        assertThrows(StorageFailure.class,()->put(f,p,1,chunk(bytes,1)));
        try(Connection c=f.open()){c.setAutoCommit(false);assertEquals(0,ChunkedPayloadStore.nextChunkToDelete(c,p));ChunkedPayloadStore.deleteChunk(c,p,0);c.commit();}
        f.tx(c->{ChunkedPayloadStore.deleteChunk(c,p,0);return null;});assertEquals(Integer.valueOf(1),f.tx(c->ChunkedPayloadStore.nextChunkToDelete(c,p)));
        f.tx(c->{ChunkedPayloadStore.deleteChunk(c,p,1);return null;});assertEquals(100,f.count("SELECT bytes FROM retained_budget WHERE id=1"));
        f.tx(c->{ChunkedPayloadStore.finishCancel(c,p);return null;});assertEquals("CANCELLED",f.state(p));assertEquals(100,f.count("SELECT bytes FROM retained_budget WHERE id=1"));
        f.tx(c->{ChunkedPayloadStore.finishCancel(c,p);ChunkedPayloadStore.cancel(c,p);return null;});
        assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_payload_manifests"));assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
        assertThrows(StorageFailure.class,()->put(f,p,0,chunk(bytes,0)));
    }
    @Test void retireMustShareCallerTransactionWithReferenceRemovalAndNeverDeletesPublishedBytesEarly()throws Exception{
        Fixture f=new Fixture();byte[] bytes=body(20);var p=plan(bytes);reserve(f,p);put(f,p,0,bytes);
        f.tx(c->{ChunkedPayloadStore.seal(c,p);ChunkedPayloadStore.publish(c,p);c.createStatement().executeUpdate("INSERT INTO business_reference VALUES(1,'"+p.id()+"')");return null;});
        assertThrows(IOException.class,()->f.tx(c->{c.createStatement().executeUpdate("DELETE FROM business_reference WHERE id=1");ChunkedPayloadStore.retire(c,p);throw new IOException("synthetic publication removal rollback");}));
        assertEquals("PUBLISHED",f.state(p));assertEquals(1,f.count("SELECT COUNT(*) FROM business_reference"));
        f.tx(c->{c.createStatement().executeUpdate("DELETE FROM business_reference WHERE id=1");ChunkedPayloadStore.retire(c,p);return null;});
        assertEquals("CANCELLING",f.state(p));assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
        f.tx(c->{ChunkedPayloadStore.deleteChunk(c,p,0);return null;});
        f.tx(c->{ChunkedPayloadStore.finishCancel(c,p);c.createStatement().executeUpdate("UPDATE retained_budget SET bytes=0 WHERE id=1");return null;});
        assertEquals(0,f.count("SELECT bytes FROM retained_budget WHERE id=1"));
    }
    @Test void corruptionAndWholeDigestMismatchRefuseSealWithoutPublishingOrDiscardingData()throws Exception{
        Fixture f=new Fixture();byte[] bytes=body(20);var p=plan(bytes);reserve(f,p);put(f,p,0,bytes);
        try(Connection c=f.open();PreparedStatement q=c.prepareStatement("UPDATE starhub_payload_chunks SET content=? WHERE payload_id=?")){byte[] wrong=bytes.clone();wrong[1]^=1;q.setBytes(1,wrong);q.setString(2,p.id());q.executeUpdate();}
        assertThrows(SQLException.class,()->f.tx(c->{ChunkedPayloadStore.seal(c,p);return null;}));assertEquals("STAGING",f.state(p));
        assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
        var q=new ChunkedPayloadStore.Plan(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"GUEST",p.ownerHash(),p.requestHash(),bytes.length,1,hash(new byte[]{99}));reserve(f,q);put(f,q,0,bytes);
        assertThrows(SQLException.class,()->f.tx(c->{ChunkedPayloadStore.seal(c,q);return null;}));assertEquals("STAGING",f.state(q));
    }
    @Test void emptyPayloadDefensiveBindingAndCallerOwnedTransactionAreEnforced()throws Exception{
        Fixture f=new Fixture();byte[] bytes={};var p=plan(bytes);byte[] before=p.ownerHash();p.ownerHash()[0]^=1;assertArrayEquals(before,p.ownerHash());
        try(Connection c=f.open()){assertThrows(SQLException.class,()->ChunkedPayloadStore.reserve(c,p));}
        f.tx(c->{ChunkedPayloadStore.reserve(c,p);ChunkedPayloadStore.seal(c,p);ChunkedPayloadStore.publish(c,p);return null;});assertArrayEquals(bytes,f.tx(c->ChunkedPayloadStore.readBytes(c,p,0)));
        assertEquals(954,ChunkedPayloadStore.chunks(2_000_000_000L));
        assertThrows(StorageFailure.class,()->ChunkedPayloadStore.chunks(2_000_000_001L));assertThrows(StorageFailure.class,()->ChunkedPayloadStore.chunks(-1));
        assertThrows(StorageFailure.class,()->new ChunkedPayloadStore.Plan(p.id(),p.operation(),p.kind(),new byte[31],p.requestHash(),0,0,p.sha256()));
    }
}
