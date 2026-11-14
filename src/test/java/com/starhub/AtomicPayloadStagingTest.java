package com.starhub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real durable H2 and separate JVMs; synthetic authority/GTID, not MySQL GR evidence. */
class AtomicPayloadStagingTest {
    @TempDir Path directory;
    static final class Fixture {
        final String url,operation,payloadId;final int size;
        final AtomicInteger confirmations=new AtomicInteger();final AtomicBoolean changed=new AtomicBoolean();
        final AtomicDatabase database;final AtomicPayloads payloads;final ChunkedPayloadStore.Plan plan;
        final byte[] bytes;
        Fixture(Path file,String operation,String payloadId,int size,String fault,boolean initialize)throws Exception {
            this.operation=operation;this.payloadId=payloadId;this.size=size;
            url="jdbc:h2:file:"+file.toAbsolutePath()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=5000";
            bytes=new byte[size];new Random(7124).nextBytes(bytes);
            if(initialize)try(Connection c=DriverManager.getConnection(url);Statement q=c.createStatement()){
                q.execute("CREATE SCHEMA a");q.execute("CREATE SCHEMA b");q.execute("SET SCHEMA a");
                q.execute("CREATE TABLE file_storage_atomic_gate(id INT PRIMARY KEY,revision BIGINT,protocol_version INT)");
                q.execute("INSERT INTO file_storage_atomic_gate VALUES(1,0,5)");
                q.execute("CREATE TABLE payload_parent(id INT PRIMARY KEY,state VARCHAR(20),reserved_bytes BIGINT,used_bytes BIGINT)");
                q.execute("INSERT INTO payload_parent VALUES(1,'RESERVED',"+size+",0)");
                q.execute("CREATE TABLE publication(payload_id CHAR(36) PRIMARY KEY)");
                String ddl=Files.readString(Path.of("deploy/migrations/012_chunked_payload_store.sql")).replaceAll("(?m)^--.*$","");
                for(String sql:ddl.split(";"))if(!sql.isBlank())q.execute(sql);
            }
            Properties p=new Properties();p.setProperty("db.url","jdbc:mysql://127.0.0.1:1/a?test=only");
            p.setProperty("storage.node2.db.url","jdbc:mysql://127.0.0.1:1/b?test=only");p.setProperty("storage.transactionMode","atomic");
            p.setProperty("storage.idempotency.secret","explicit-synthetic-payload-secret-not-a-production-credential");
            p.setProperty("vault.key",Base64.getEncoder().encodeToString(new byte[32]));
            ReplicaAuthority.Lease lease=new ReplicaAuthority.Lease("11111111-1111-4111-8111-111111111111","a".repeat(64),"synthetic-view","1","dual","22222222-2222-4222-8222-222222222222","b".repeat(64));
            AtomicBoolean lose=new AtomicBoolean(true);
            AtomicDatabase.Authority authority=new AtomicDatabase.Authority(){
                public ReplicaAuthority.Lease acquire(){return lease;}
                public void unchanged(ReplicaAuthority.Lease expected)throws SQLException{if(changed.get()||!lease.equals(expected))throw new SQLException("Synthetic authority changed");}
                public void confirm(ReplicaAuthority.Lease expected,String gtid,String op)throws SQLException{
                    unchanged(expected);confirmations.incrementAndGet();
                    if(fault.equals("first-confirm")&&count("SELECT COUNT(*) FROM starhub_payload_chunks")==1&&lose.getAndSet(false))throw new SQLException("Synthetic confirmation reply lost");
                }
            };
            database=new AtomicDatabase(p,()->connection(fault,lose),authority);payloads=new AtomicPayloads(database,p);
            plan=payloads.plan(payloadId,operation,"TEST","synthetic-owner:generation-1","request:"+operation,bytes);
        }
        Connection open()throws SQLException{Connection c=DriverManager.getConnection(url);c.setSchema("a");return c;}
        long count(String sql)throws SQLException{try(Connection c=open();Statement q=c.createStatement();ResultSet r=q.executeQuery(sql)){if(!r.next())throw new SQLException("Synthetic row missing");return r.getLong(1);}}
        String state()throws Exception{try(Connection c=open()){c.setAutoCommit(false);try{return ChunkedPayloadStore.state(c,plan);}finally{c.rollback();}}}
        Connection connection(String fault,AtomicBoolean lose)throws SQLException {
            Connection real=open();
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,m,args)->{
                try{
                    Object value=m.invoke(real,args);
                    if(m.getName().equals("commit")&&Set.of("first-commit","last-commit").contains(fault)){
                        long chunks=count("SELECT COUNT(*) FROM starhub_payload_chunks");
                        if(chunks==(fault.equals("first-commit")?1:plan.chunkCount())&&lose.getAndSet(false))throw new SQLException("Synthetic physical commit acknowledgement lost","08006");
                    }
                    if(m.getName().equals("createStatement")){
                        Statement q=(Statement)value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(p,method,a)->{
                            if(method.getName().equals("executeQuery")&&"SELECT @@global.gtid_executed".equals(a[0]))a=new Object[]{"SELECT 'synthetic-gtid:1-100'"};
                            try{return method.invoke(q,a);}catch(InvocationTargetException failed){throw failed.getCause();}
                        });
                    }
                    return value;
                }catch(InvocationTargetException failed){throw failed.getCause();}
            });
        }
        void parent(Connection c)throws Exception {
            try(PreparedStatement q=c.prepareStatement("SELECT state,reserved_bytes FROM payload_parent WHERE id=1 FOR UPDATE");ResultSet r=q.executeQuery()){
                if(!r.next()||!r.getString(1).equals("RESERVED")||r.getLong(2)!=size)throw new StorageFailure(409,"PARENT_CHANGED","Synthetic parent no longer authorizes staging");
            }
        }
        void stage()throws Exception{payloads.stage(plan,bytes,this::parent);}
        void verifiedBytes()throws Exception{try(Connection c=open()){c.setAutoCommit(false);try{assertArrayEquals(bytes,ChunkedPayloadStore.readBytes(c,plan,size));}finally{c.rollback();}}}
        void publish()throws Exception {
            database.run(operation,c->{parent(c);ChunkedPayloadStore.publish(c,plan);
                try(PreparedStatement q=c.prepareStatement("INSERT INTO publication(payload_id) VALUES(?)")){q.setString(1,payloadId);q.executeUpdate();}
                c.createStatement().executeUpdate("UPDATE payload_parent SET state='COMMITTED',used_bytes=reserved_bytes,reserved_bytes=0 WHERE id=1");return null;});
        }
    }
    Fixture fixture(int size)throws Exception{return new Fixture(directory.resolve("payload"),UUID.randomUUID().toString(),UUID.randomUUID().toString(),size,"none",true);}
    void child(Fixture f,String mode,int expected)throws Exception {
        List<String> command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-cp",System.getProperty("java.class.path"),ProcessWorker.class.getName(),directory.resolve("payload").toAbsolutePath().toString(),f.operation,f.payloadId,Integer.toString(f.size),mode);
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        try{assertTrue(process.waitFor(40,TimeUnit.SECONDS),"Synthetic child did not terminate");String result=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(expected,process.exitValue(),result);}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
    public static final class ProcessWorker {
        public static void main(String[] args)throws Exception {
            Fixture f=new Fixture(Path.of(args[0]),args[1],args[2],Integer.parseInt(args[3]),args[4],false);
            try{
                if(args[4].equals("read"))f.verifiedBytes();else f.stage();
                System.out.println("SYNTHETIC_PAYLOAD_OK");
            }catch(SQLException|StorageFailure unknown){System.out.println("SYNTHETIC_PAYLOAD_UNCONFIRMED");System.exit(20);}
        }
    }
    @Test void twoChunksUseTwoConfirmedTransactionsAndRemainUnpublishedUntilLaterCommit()throws Exception {
        Fixture f=fixture(ChunkedPayloadStore.CHUNK_BYTES+37);f.stage();
        assertEquals(2,f.confirmations.get());assertEquals(2,f.count("SELECT revision FROM file_storage_atomic_gate"));
        assertEquals("READY",f.state());assertEquals(0,f.count("SELECT COUNT(*) FROM publication"));
        assertEquals(f.size,f.count("SELECT reserved_bytes FROM payload_parent"));assertEquals(0,f.count("SELECT used_bytes FROM payload_parent"));
        child(f,"read",0);f.publish();assertEquals("PUBLISHED",f.state());assertEquals(f.size,f.count("SELECT used_bytes FROM payload_parent"));assertEquals(0,f.count("SELECT reserved_bytes FROM payload_parent"));
    }
    @Test void independentJvmsResumeOriginalBytesAfterFirstPhysicalCommitOrConfirmationReplyIsLost()throws Exception {
        for(String fault:List.of("first-commit","first-confirm")){
            Path sub=Files.createDirectory(directory.resolve(fault));Fixture f=new Fixture(sub.resolve("payload"),UUID.randomUUID().toString(),UUID.randomUUID().toString(),ChunkedPayloadStore.CHUNK_BYTES+13,"none",true);
            Process first=process(sub,f,fault);assertProcess(first,20);assertEquals("STAGING",f.state());assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
            assertEquals(f.size,f.count("SELECT reserved_bytes FROM payload_parent"));assertEquals(0,f.count("SELECT COUNT(*) FROM publication"));
            assertProcess(process(sub,f,"none"),0);assertEquals("READY",f.state());assertEquals(2,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));f.verifiedBytes();
        }
    }
    Process process(Path sub,Fixture f,String mode)throws IOException{return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-cp",System.getProperty("java.class.path"),ProcessWorker.class.getName(),sub.resolve("payload").toAbsolutePath().toString(),f.operation,f.payloadId,Integer.toString(f.size),mode).redirectErrorStream(true).start();}
    void assertProcess(Process process,int expected)throws Exception{try{assertTrue(process.waitFor(40,TimeUnit.SECONDS));assertEquals(expected,process.exitValue(),new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}finally{if(process.isAlive())process.destroyForcibly();}}
    @Test void lastChunkUnknownCommitLeavesCompleteReadyBytesButNoPrematurePublication()throws Exception {
        Fixture f=fixture(ChunkedPayloadStore.CHUNK_BYTES+29);child(f,"last-commit",20);
        assertEquals("READY",f.state());assertEquals(0,f.count("SELECT COUNT(*) FROM publication"));assertEquals(f.size,f.count("SELECT reserved_bytes FROM payload_parent"));
        child(f,"none",0);f.verifiedBytes();assertEquals(2,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
    }
    @Test void cancellationVisibleToAnotherJvmRejectsLateStagingAndRetainsBudget()throws Exception {
        Fixture f=fixture(ChunkedPayloadStore.CHUNK_BYTES+7);child(f,"first-commit",20);
        f.database.run(f.operation,c->{f.parent(c);ChunkedPayloadStore.cancel(c,f.plan);c.createStatement().executeUpdate("UPDATE payload_parent SET state='CANCELLING' WHERE id=1");return null;});
        child(f,"none",20);assertEquals("CANCELLING",f.state());assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertEquals(f.size,f.count("SELECT reserved_bytes FROM payload_parent"));assertEquals(0,f.count("SELECT COUNT(*) FROM publication"));
    }
    @Test void failedAfterChunkCallbackRollsBackHeaderBytesAndReadyTogether()throws Exception {
        Fixture f=fixture(37);
        assertThrows(IOException.class,()->f.payloads.stage(f.plan,f.bytes,f::parent,c->{throw new IOException("Synthetic capacity failure before commit");}));
        assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_payload_manifests"));assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertEquals(f.size,f.count("SELECT reserved_bytes FROM payload_parent"));assertEquals(0,f.confirmations.get());
        child(f,"none",0);assertEquals("READY",f.state());
    }
    @Test void changedOwnerRequestOrPayloadNeverOverwritesAnExistingImmutableChunk()throws Exception {
        Fixture f=fixture(37);f.stage();byte[] changed=f.bytes.clone();changed[0]^=1;
        assertThrows(SQLException.class,()->f.payloads.stage(f.plan,changed,f::parent));
        var wrongOwner=f.payloads.plan(f.payloadId,f.operation,"TEST","different-owner","request:"+f.operation,f.bytes);
        var wrongRequest=f.payloads.plan(f.payloadId,f.operation,"TEST","synthetic-owner:generation-1","different-request",f.bytes);
        for(var wrong:List.of(wrongOwner,wrongRequest))assertEquals(409,assertThrows(StorageFailure.class,()->f.payloads.stage(wrong,f.bytes,f::parent)).status);
        f.verifiedBytes();assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
    }
    @Test void permitChangeBeforeFusedCommitRollsBackAllNewRowsAndKeepsReservation()throws Exception {
        Fixture f=fixture(37);
        assertThrows(SQLException.class,()->f.payloads.stage(f.plan,f.bytes,c->{f.parent(c);f.changed.set(true);}));
        assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_payload_manifests"));assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertEquals(f.size,f.count("SELECT reserved_bytes FROM payload_parent"));assertEquals(0,f.confirmations.get());
    }
    @Test void emptyPayloadStillGetsItsOwnConfirmedSealTransaction()throws Exception {
        Fixture f=fixture(0);f.stage();assertEquals(1,f.confirmations.get());assertEquals("READY",f.state());assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));child(f,"read",0);
    }
}
