package com.starhub;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real local SQL tests; GTID/controller probes are explicit test adapters, not GR evidence. */
class AtomicTransactionsTest {
    static final class Fixture implements AutoCloseable {
        final Properties p=new Properties();final String url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000";
        final String generation=UUID.randomUUID().toString();final byte[] accountHash=new byte[]{1,2,3};
        final AtomicBoolean changePermit=new AtomicBoolean(),rejectConfirm=new AtomicBoolean(),loseCommit=new AtomicBoolean(),losePublishedCommit=new AtomicBoolean();final AtomicInteger confirms=new AtomicInteger();
        final AtomicLong availableBytes=new AtomicLong(50_000_000_000L);
        final HttpServer probes;final AtomicDatabase.Authority authority;final AtomicDatabase db;final AtomicFileStorage files;
        Fixture()throws Exception {
            p.setProperty("db.url","jdbc:mysql://127.0.0.1:23516/a?x=1");p.setProperty("storage.node2.db.url","jdbc:mysql://127.0.0.1:23516/b?x=1");
            p.setProperty("storage.replicated","true");p.setProperty("storage.transactionMode","atomic");p.setProperty("server.id","1");
            p.setProperty("storage.physicalNodeIds","1,2");
            p.setProperty("storage.idempotency.secret",UUID.randomUUID()+UUID.randomUUID().toString());p.setProperty("storage.probe.token",UUID.randomUUID().toString());p.setProperty("storage.ingress.token",UUID.randomUUID().toString());
            probes=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            probes.createContext("/status",exchange->{
                int node=exchange.getRequestURI().getQuery().endsWith("1")?1:2;
                var value=ModelGateway.JSON.createObjectNode().put("nodeId",node).put("observedAtMillis",System.currentTimeMillis()).put("mysqlReady",true).put("tomcatReady",true).put("reserveGuaranteed",true)
                        .put("capacityModelValidated",true).put("physicalBytesPerPayloadByte",2).put("fixedOverheadBytes",1024).put("replicationMode","dual").put("singleNodeAuthorized",false);
                value.put("viewId","synthetic-view").put("membershipDigest","a".repeat(64)).put("memberUuid","synthetic-member-"+node);
                value.putArray("onlineNodeIds").add(1).add(2);value.putArray("configuredNodeIds").add(1).add(2);
                value.putArray("volumes").addObject().put("id","EXPLICIT-TEST-SIMULATED-CAPACITY").put("availableBytes",availableBytes.get());
                byte[] body=ModelGateway.JSON.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });probes.start();
            ProbeHttpFixture.endpoints(p);
            var lease=new ReplicaAuthority.Lease(UUID.randomUUID().toString(),"a".repeat(64),"test-view","1","dual",UUID.randomUUID().toString(),"b".repeat(64));
            authority=new AtomicDatabase.Authority(){
                public ReplicaAuthority.Lease acquire(){return lease;}
                public void unchanged(ReplicaAuthority.Lease ignored)throws SQLException{if(changePermit.get())throw new SQLException("Test permit changed");}
                public void confirm(ReplicaAuthority.Lease ignored,String gtid,String op)throws SQLException{confirms.incrementAndGet();if(rejectConfirm.get())throw new SQLException("Test post-commit confirmation unavailable");}
            };
            try(Connection c=DriverManager.getConnection(url);Statement q=c.createStatement()){
                for(String schema:List.of("a","b"))q.execute("CREATE SCHEMA "+schema);
                q.execute("SET SCHEMA a");
                q.execute("CREATE TABLE file_storage_atomic_gate(id TINYINT PRIMARY KEY,revision BIGINT NOT NULL,protocol_version INT NOT NULL)");q.execute("INSERT INTO file_storage_atomic_gate VALUES(1,0,5)");
                q.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,active BOOLEAN NOT NULL DEFAULT TRUE,generation CHAR(36),email VARCHAR(254),password_hash VARBINARY(32),password_salt VARBINARY(16))");
                try(PreparedStatement insert=c.prepareStatement("INSERT INTO users(id,active,generation,email,password_hash) VALUES(1,TRUE,?,'user@example.test',?)")){insert.setString(1,generation);insert.setBytes(2,accountHash);insert.executeUpdate();}
                q.execute("CREATE TABLE conversations(user_id BIGINT,chat_id VARCHAR(100),title VARCHAR(200),payload CLOB,revision BIGINT,PRIMARY KEY(user_id,chat_id))");
                q.execute("CREATE TABLE file_storage_users(user_id BIGINT PRIMARY KEY,generation CHAR(36),used_bytes BIGINT,reserved_bytes BIGINT DEFAULT 0,active_op CHAR(36))");
                q.execute("CREATE TABLE library_files(id CHAR(36) PRIMARY KEY,user_id BIGINT,name VARCHAR(255),mime VARCHAR(100),size_bytes INT,content BLOB,storage_node_id TINYINT,owner_generation CHAR(36),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,deleted_at TIMESTAMP)");
                for(String schema:List.of("a","b"))q.execute("CREATE TABLE "+schema+".file_blobs(id CHAR(36) PRIMARY KEY,user_id BIGINT,owner_generation CHAR(36),content BLOB,sha256 BINARY(32),size_bytes INT)");
                q.execute("CREATE TABLE file_storage_operations(op_id CHAR(36) PRIMARY KEY,user_id BIGINT,user_generation CHAR(36),request_hash BINARY(32),kind VARCHAR(10),node_id TINYINT,state VARCHAR(16),logical_bytes BIGINT,physical_bytes BIGINT,primary_physical_bytes BIGINT,files_json CLOB,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                q.execute("CREATE TABLE starhub_upload_reservations(op_id CHAR(36) PRIMARY KEY,owner_node_id TINYINT,owner_mysql_uuid CHAR(36),owner_epoch VARCHAR(20),owner_permit CHAR(64),reserved_view VARCHAR(200))");
                q.execute("CREATE TABLE starhub_capacity_totals(node_id TINYINT PRIMARY KEY,reserved_bytes BIGINT NOT NULL)");q.execute("INSERT INTO starhub_capacity_totals VALUES(1,0),(2,0)");
                q.execute("CREATE TABLE starhub_reserved_capacity(op_id CHAR(36),node_id TINYINT,reserved_bytes BIGINT,PRIMARY KEY(op_id,node_id))");
                q.execute("CREATE TABLE starhub_request_results(operation_id CHAR(36) PRIMARY KEY,user_id BIGINT,user_generation CHAR(36),endpoint VARCHAR(120),request_hash BINARY(32),response_status SMALLINT,response_type VARCHAR(120),response_body BLOB)");
                q.execute("CREATE TABLE starhub_login_limits(identity_hash BINARY(32) PRIMARY KEY,window_start_millis BIGINT,failures INT)");
                q.execute("CREATE TABLE starhub_login_attempts(operation_id CHAR(36) PRIMARY KEY,identity_hash BINARY(32),request_hash BINARY(32),email_hash BINARY(32),outcome_status SMALLINT)");
                q.execute("CREATE TABLE starhub_login_status_limits(identity_hash BINARY(32) PRIMARY KEY,window_start_millis BIGINT,requests INT)");
                q.execute("CREATE TABLE starhub_provider_operations(operation_id CHAR(36) PRIMARY KEY,owner_hash BINARY(32),request_hash BINARY(32),kind VARCHAR(120),state VARCHAR(32),account_user_id BIGINT,account_generation CHAR(36),image_budget_bytes BIGINT DEFAULT 0,logical_reserved BIGINT DEFAULT 0,dispatch_hash BINARY(32),reserved_view VARCHAR(200),response_status SMALLINT,result_cipher BLOB,created_millis BIGINT,updated_millis BIGINT)");
                q.execute("CREATE TABLE starhub_provider_capacity(operation_id CHAR(36),node_id TINYINT,reserved_bytes BIGINT,PRIMARY KEY(operation_id,node_id))");
                for(String migration:List.of("010_atomic_guest_sessions.sql","012_chunked_payload_store.sql","013_chunked_business_manifests.sql")){
                    StringBuilder ddl=new StringBuilder();for(String line:Files.readString(Path.of("deploy/migrations/"+migration)).split("\n"))if(!line.stripLeading().startsWith("--"))ddl.append(line).append('\n');for(String statement:ddl.toString().split(";"))if(!statement.isBlank())q.execute(statement.replace("ENGINE=InnoDB",""));
                }
                q.execute("CREATE TABLE business(id INT PRIMARY KEY,value_text VARCHAR(100))");q.execute("CREATE TABLE b.business(id INT PRIMARY KEY,value_text VARCHAR(100))");
            }
            db=new AtomicDatabase(p,this::connection,authority);files=new AtomicFileStorage(db,cluster());
        }
        StorageCluster cluster()throws Exception{return new StorageCluster(p,ProbeHttpFixture.synthetic(probes));}
        Connection connection()throws SQLException {
            Connection real=DriverManager.getConnection(url);real.setSchema("a");
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,m,args)->{
                try{
                    boolean losePublication=false;if(m.getName().equals("commit")&&losePublishedCommit.get())try(Statement check=real.createStatement();ResultSet published=check.executeQuery("SELECT COUNT(*) FROM library_files")){published.next();losePublication=published.getLong(1)>0;}
                    Object value=m.invoke(real,args);
                    if(m.getName().equals("commit")&&(losePublication&&losePublishedCommit.getAndSet(false)||loseCommit.getAndSet(false)))throw new SQLException("Test commit acknowledgment lost","08006");
                    if(m.getName().equals("createStatement")){
                        Statement statement=(Statement)value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(p,method,a)->{
                            if(method.getName().equals("executeQuery")&&a[0].equals("SELECT @@global.gtid_executed"))a=new Object[]{"SELECT 'test-gtid:1-100'"};
                            try{return method.invoke(statement,a);}catch(InvocationTargetException e){throw e.getCause();}
                        });
                    }
                    return value;
                }catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }
        long count(String sql)throws Exception{try(Connection c=connection();ResultSet r=c.createStatement().executeQuery(sql)){r.next();return r.getLong(1);}}
        HttpServletRequest ingress(){HttpServletRequest r=mock(HttpServletRequest.class);when(r.getHeader("X-Starhub-Ingress-Node")).thenReturn("2");when(r.getHeader("X-Starhub-Ingress-Token")).thenReturn(p.getProperty("storage.ingress.token"));return r;}
        public void close(){probes.stop(0);try(Connection c=DriverManager.getConnection(url);Statement q=c.createStatement()){q.execute("SHUTDOWN");}catch(SQLException failure){throw new AssertionError("Synthetic H2 shutdown failed",failure);}}
    }
    @Test void businessSchemasAndRemoteSchemasCannotStartLegacyRecovery(){
        Properties p=new Properties();p.setProperty("db.url","jdbc:mysql://127.0.0.1:331/starhub_accounts?x=1");p.setProperty("storage.node2.db.url","jdbc:mysql://127.0.0.1:331/starhub_storage_test_peer?x=1");
        assertThrows(IllegalArgumentException.class,()->FileStorage.requireIsolatedLegacy(p));
        p.setProperty("db.url","jdbc:mysql://127.0.0.1:331/starhub_storage_test_main?x=1");assertDoesNotThrow(()->FileStorage.requireIsolatedLegacy(p));
        p.setProperty("storage.node2.db.url","jdbc:mysql://192.0.2.1:331/starhub_storage_test_peer?x=1");assertThrows(IllegalArgumentException.class,()->FileStorage.requireIsolatedLegacy(p));
    }
    @Test void oneSqlRollbackCoversBothLogicalSchemasAndTheGlobalRevision()throws Exception {
        try(Fixture f=new Fixture()){
            assertThrows(IOException.class,()->f.db.run(UUID.randomUUID().toString(),c->{c.createStatement().executeUpdate("INSERT INTO business VALUES(1,'a')");c.createStatement().executeUpdate("INSERT INTO "+f.db.table(2,"business")+" VALUES(1,'b')");throw new IOException("Synthetic business failure");}));
            assertEquals(0,f.count("SELECT COUNT(*) FROM a.business"));assertEquals(0,f.count("SELECT COUNT(*) FROM b.business"));assertEquals(0,f.count("SELECT revision FROM file_storage_atomic_gate"));
        }
    }
    @Test void lostCommitReplyPreservesOneFileQuotaAndReplayResult()throws Exception {
        try(Fixture f=new Fixture()){
            String op=UUID.randomUUID().toString();byte[] content=new byte[4096];new Random(15).nextBytes(content);f.loseCommit.set(true);
            assertThrows(SQLException.class,()->f.files.upload(f.ingress(),1,op,List.of(new FileStorage.Upload("test.txt","text/plain",content))));
            assertEquals(4096,f.count("SELECT reserved_bytes FROM file_storage_users"));assertEquals(0,f.count("SELECT COUNT(*) FROM library_files"));
            var recovered=f.files.upload(f.ingress(),1,op,List.of(new FileStorage.Upload("test.txt","text/plain",content)));
            assertEquals("COMMITTED",recovered.path("state").asText());assertEquals(1,f.count("SELECT COUNT(*) FROM library_files"));assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));assertEquals(4096,f.count("SELECT used_bytes FROM file_storage_users"));assertEquals(0,f.count("SELECT reserved_bytes FROM file_storage_users"));
            assertArrayEquals(content,Base64.getDecoder().decode(f.files.read(1,recovered.path("id").asText()).path("data").asText()));
            var conflict=assertThrows(StorageFailure.class,()->f.files.upload(f.ingress(),1,op,List.of(new FileStorage.Upload("changed.txt","text/plain",content))));assertEquals(409,conflict.status);
        }
    }
    private static com.fasterxml.jackson.databind.node.ArrayNode plan(String name,byte[] content){
        var result=ModelGateway.JSON.createArrayNode();result.addObject().put("name",name).put("mime","text/plain").put("size",content.length).put("sha256",Base64.getEncoder().encodeToString(FileStorage.hash(content)));return result;
    }
    @Test void durablePendingQuotaIsVisibleToAnotherCoordinatorBeforeAnyBodyArrives()throws Exception {
        try(Fixture f=new Fixture()){
            AtomicFileStorage other=new AtomicFileStorage(new AtomicDatabase(f.p,f::connection,f.authority),f.cluster());String op=UUID.randomUUID().toString();byte[] data=new byte[8];
            f.files.list(1,ModelGateway.JSON.createObjectNode());
            try(Connection c=f.connection()){c.createStatement().executeUpdate("UPDATE file_storage_users SET used_bytes=1999999990 WHERE user_id=1");}
            var reserved=f.files.reserve(1,op,plan("pending.txt",data),2);assertEquals("RESERVED",reserved.path("state").asText());
            var list=other.list(1,ModelGateway.JSON.createObjectNode());assertEquals(1_999_999_998L,list.path("used").asLong());assertEquals(8,list.path("reservedBytes").asLong());assertEquals(op,list.path("pendingUploads").get(0).path("operationId").asText());assertEquals(0,f.count("SELECT COUNT(*) FROM library_files"));
            assertEquals(413,assertThrows(StorageFailure.class,()->other.reserve(1,UUID.randomUUID().toString(),plan("over.txt",new byte[3]),1)).status);
            var repeated=other.reserve(1,op,plan("pending.txt",data),1);assertEquals(reserved.path("id").asText(),repeated.path("id").asText());assertEquals(8,f.count("SELECT reserved_bytes FROM file_storage_users"));
            assertEquals("CANCELLED",other.cancel(1,op).path("state").asText());assertEquals("CANCELLED",f.files.cancel(1,op).path("state").asText());assertEquals(0,f.count("SELECT reserved_bytes FROM file_storage_users"));assertEquals(0,f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));
            assertEquals("OPERATION_ABORTED",assertThrows(StorageFailure.class,()->f.files.complete(f.ingress(),1,op,List.of(new FileStorage.Upload("pending.txt","text/plain",data)))).code);
        }
    }
    @Test void lostContentCommitReplyReplaysFixedIdsWithoutChargingOrWritingTwice()throws Exception {
        try(Fixture f=new Fixture()){
            String op=UUID.randomUUID().toString();byte[] data=new byte[4096];new Random(19).nextBytes(data);
            String file=f.files.reserve(1,op,plan("content.txt",data),2).path("id").asText();f.losePublishedCommit.set(true);
            assertThrows(SQLException.class,()->f.files.complete(f.ingress(),1,op,List.of(new FileStorage.Upload("content.txt","text/plain",data))));
            assertEquals(4096,f.count("SELECT used_bytes FROM file_storage_users"));assertEquals(0,f.count("SELECT reserved_bytes FROM file_storage_users"));assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));
            assertEquals(file,f.files.complete(f.ingress(),1,op,List.of(new FileStorage.Upload("content.txt","text/plain",data))).path("id").asText());
            assertEquals(1,f.count("SELECT COUNT(*) FROM library_files"));assertEquals(0,f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));
        }
    }
    @Test void changedViewRequiresExplicitSameOperationReservationBeforeContent()throws Exception {
        try(Fixture f=new Fixture()){
            String op=UUID.randomUUID().toString();byte[] data=new byte[]{1,2};String file=f.files.reserve(1,op,plan("view.txt",data),2).path("id").asText();
            var current=new ReplicaAuthority.Lease(UUID.randomUUID().toString(),"c".repeat(64),"new-test-view","2","dual",UUID.randomUUID().toString(),"d".repeat(64));
            AtomicDatabase.Authority changed=new AtomicDatabase.Authority(){public ReplicaAuthority.Lease acquire(){return current;}public void unchanged(ReplicaAuthority.Lease ignored){}public void confirm(ReplicaAuthority.Lease ignored,String gtid,String operation){}};
            AtomicFileStorage other=new AtomicFileStorage(new AtomicDatabase(f.p,f::connection,changed),f.cluster());
            assertEquals("RESERVATION_VIEW_CHANGED",assertThrows(StorageFailure.class,()->other.complete(f.ingress(),1,op,List.of(new FileStorage.Upload("view.txt","text/plain",data)))).code);
            assertEquals(file,other.reserve(1,op,plan("view.txt",data),1).path("id").asText());assertEquals(2,f.count("SELECT reserved_bytes FROM file_storage_users"));
            assertEquals(file,other.complete(f.ingress(),1,op,List.of(new FileStorage.Upload("view.txt","text/plain",data))).path("id").asText());
        }
    }
    @Test void insufficientPhysicalBudgetRejectsBeforeQuotaOrPendingStateIsPromised()throws Exception {
        try(Fixture f=new Fixture()){
            f.availableBytes.set(StorageCluster.FLOOR+1);String op=UUID.randomUUID().toString();
            assertEquals(507,assertThrows(StorageFailure.class,()->f.files.reserve(1,op,plan("no-space.txt",new byte[]{1}),2)).status);
            assertEquals(0,f.count("SELECT COUNT(*) FROM file_storage_operations"));assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_reserved_capacity"));assertEquals(0,f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));
        }
    }
    @Test void reservationCannotBeConsumedInTheSameUnconfirmedTransaction()throws Exception {
        try(Fixture f=new Fixture();AtomicDatabase.Scope outer=f.db.begin(UUID.randomUUID().toString())){
            String op=UUID.randomUUID().toString();f.files.reserve(1,op,plan("a.txt",new byte[]{1}),1);
            assertThrows(SQLException.class,()->f.files.complete(f.ingress(),1,op,List.of(new FileStorage.Upload("a.txt","text/plain",new byte[]{1}))));
        }
    }
    @Test void contentAndExplicitCancelChooseOneTerminalOutcomeWithoutDeletingCommittedBytes()throws Exception {
        try(Fixture f=new Fixture()){
            AtomicFileStorage other=new AtomicFileStorage(new AtomicDatabase(f.p,f::connection,f.authority),f.cluster());String op=UUID.randomUUID().toString();byte[] data=new byte[1024];new Random(17).nextBytes(data);
            String id=f.files.reserve(1,op,plan("race.txt",data),2).path("id").asText();var start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
            try{
                Future<String> content=pool.submit(()->{start.await();try{return f.files.complete(f.ingress(),1,op,List.of(new FileStorage.Upload("race.txt","text/plain",data))).path("state").asText();}catch(StorageFailure cancelled){assertEquals("OPERATION_ABORTED",cancelled.code);return "CANCELLED";}});
                Future<String> cancel=pool.submit(()->{start.await();return other.cancel(1,op).path("state").asText();});start.countDown();String a=content.get(5,TimeUnit.SECONDS),b=cancel.get(5,TimeUnit.SECONDS);assertEquals(a,b);
                assertEquals(0,f.count("SELECT reserved_bytes FROM file_storage_users"));assertEquals(0,f.count("SELECT SUM(reserved_bytes) FROM starhub_capacity_totals"));
                if(a.equals("COMMITTED")){assertEquals("COMMITTED",other.cancel(1,op).path("state").asText());assertArrayEquals(data,Base64.getDecoder().decode(f.files.read(1,id).path("data").asText()));}
                else{assertEquals(0,f.count("SELECT COUNT(*) FROM library_files"));assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_payload_chunks"));}
            }finally{pool.shutdownNow();}
        }
    }
    @Test void delayedTrashReplayCannotUndoALaterRestore()throws Exception {
        try(Fixture f=new Fixture()){
            String file=f.files.upload(f.ingress(),1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("a.txt","text/plain",new byte[]{1}))).path("id").asText();String trash=UUID.randomUUID().toString();
            f.files.trash(1,trash,file,false);f.files.trash(1,UUID.randomUUID().toString(),file,true);f.files.trash(1,trash,file,false);
            assertEquals(0,f.count("SELECT COUNT(*) FROM library_files WHERE deleted_at IS NOT NULL"));assertEquals(1,f.count("SELECT used_bytes FROM file_storage_users"));
        }
    }
    @Test void borrowedConnectionsCannotCommitCloseOrUnwrapTheOuterTransaction()throws Exception {
        try(Fixture f=new Fixture();AtomicDatabase.Scope scope=f.db.begin(UUID.randomUUID().toString())){
            Connection borrowed=AtomicDatabase.borrowed(f.p);Statement statement=borrowed.createStatement();statement.executeUpdate("INSERT INTO business VALUES(1,'uncommitted')");borrowed.commit();borrowed.close();
            assertEquals(0,f.count("SELECT COUNT(*) FROM business"));assertSame(borrowed,statement.getConnection());
            try(ResultSet rows=statement.executeQuery("SELECT 1")){assertSame(borrowed,rows.getStatement().getConnection());}
            assertThrows(SQLException.class,()->borrowed.unwrap(Connection.class));assertThrows(SQLException.class,()->statement.unwrap(Statement.class));
            scope.commit();assertEquals(1,f.count("SELECT COUNT(*) FROM business"));
        }
    }
    @Test void anotherCoordinatorWaitsForTheLiveDatabaseTransactionInsteadOfAbortingIt()throws Exception {
        try(Fixture f=new Fixture()){
            AtomicDatabase other=new AtomicDatabase(f.p,f::connection,f.authority);ExecutorService pool=Executors.newSingleThreadExecutor();
            try(AtomicDatabase.Scope first=f.db.begin(UUID.randomUUID().toString())){
                first.connection.createStatement().executeUpdate("INSERT INTO business VALUES(1,'live')");
                Future<Long> waiting=pool.submit(()->other.run(UUID.randomUUID().toString(),c->{try(ResultSet r=c.createStatement().executeQuery("SELECT COUNT(*) FROM business")){r.next();return r.getLong(1);}}));
                Thread.sleep(100);assertFalse(waiting.isDone());first.commit();assertEquals(1,waiting.get(5,TimeUnit.SECONDS));
            }finally{pool.shutdownNow();}
        }
    }
    @Test void permitLossBeforeCommitRollsBackAndLossAfterCommitRemainsUnknown()throws Exception {
        try(Fixture f=new Fixture()){
            assertThrows(SQLException.class,()->f.db.run(UUID.randomUUID().toString(),c->{c.createStatement().executeUpdate("INSERT INTO business VALUES(1,'before')");f.changePermit.set(true);return null;}));
            assertEquals(0,f.count("SELECT COUNT(*) FROM business"));f.changePermit.set(false);f.rejectConfirm.set(true);
            assertThrows(SQLException.class,()->f.db.run(UUID.randomUUID().toString(),c->{c.createStatement().executeUpdate("INSERT INTO business VALUES(2,'after')");return null;}));
            assertEquals(1,f.count("SELECT COUNT(*) FROM business"));assertEquals(1,f.confirms.get());
        }
    }
    @Test void httpSuccessIsBufferedAndAConfirmedReplayDoesNotExecuteTheBusinessWorkAgain()throws Exception {
        try(Fixture f=new Fixture()){
            Path config=Files.createTempFile("starhub-atomic-synthetic-", ".properties");String previous=System.getProperty("starhub.account.config");
            try{
                try(OutputStream out=Files.newOutputStream(config)){f.p.store(out,"Synthetic test configuration");}System.setProperty("starhub.account.config",config.toString());
                String op=UUID.randomUUID().toString();HttpServletRequest req=request(f,op);AtomicInteger runs=new AtomicInteger();f.rejectConfirm.set(true);Capture failure=new Capture();
                FilterChain work=(request,response)->{
                    runs.incrementAndGet();try(Connection c=AccountServlet.db(f.p)){c.createStatement().executeUpdate("INSERT INTO business VALUES(7,'once')");c.commit();}
                    catch(SQLException e){throw new ServletException(e);}
                    response.setContentType("application/json");response.getOutputStream().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));assertEquals(0,failure.bytes.size());
                };
                AtomicHttp.run(req,failure.response,work,f.db,f.p,()->{},f.files::transientCapacity);assertEquals(503,failure.status.get());assertFalse(failure.bytes.toString(StandardCharsets.UTF_8).contains("\"ok\":true"));
                assertEquals(1,f.count("SELECT COUNT(*) FROM business"));assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_request_results"));
                f.rejectConfirm.set(false);Capture replay=new Capture();AtomicHttp.run(request(f,op),replay.response,(a,b)->fail("Replay reran the mutation"),f.db,f.p,()->{},f.files::transientCapacity);
                assertEquals(200,replay.status.get());assertTrue(replay.bytes.toString(StandardCharsets.UTF_8).contains("\"ok\":true"));assertEquals(1,runs.get());
            }finally{if(previous==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",previous);Files.deleteIfExists(config);}
        }
    }
    @Test void innerRollbackCannotBeTurnedIntoAnHttpSuccess()throws Exception {
        try(Fixture f=new Fixture()){
            Path config=Files.createTempFile("starhub-atomic-synthetic-", ".properties");String previous=System.getProperty("starhub.account.config");
            try{
                try(OutputStream out=Files.newOutputStream(config)){f.p.store(out,"Synthetic");}System.setProperty("starhub.account.config",config.toString());Capture result=new Capture();
                AtomicHttp.run(request(f,UUID.randomUUID().toString()),result.response,(request,response)->{
                    try(Connection c=AccountServlet.db(f.p)){c.createStatement().executeUpdate("INSERT INTO business VALUES(4,'rollback')");c.rollback();}catch(SQLException e){throw new ServletException(e);}
                    response.getOutputStream().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                },f.db,f.p,()->{},f.files::transientCapacity);
                assertEquals(503,result.status.get());assertEquals(0,f.count("SELECT COUNT(*) FROM business"));assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_request_results"));
            }finally{if(previous==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",previous);Files.deleteIfExists(config);}
        }
    }
    @Test void normalValidationRollbackPreservesTheKnownHttpFailure()throws Exception {
        try(Fixture f=new Fixture()){
            Path config=Files.createTempFile("starhub-atomic-synthetic-", ".properties");String previous=System.getProperty("starhub.account.config");
            try{
                try(OutputStream out=Files.newOutputStream(config)){f.p.store(out,"Synthetic");}System.setProperty("starhub.account.config",config.toString());Capture result=new Capture();
                AtomicHttp.run(request(f,UUID.randomUUID().toString()),result.response,(request,response)->{
                    try(Connection c=AccountServlet.db(f.p)){c.createStatement().executeUpdate("INSERT INTO business VALUES(4,'rollback')");c.rollback();}catch(SQLException e){throw new ServletException(e);}
                    ((HttpServletResponse)response).setStatus(400);response.getOutputStream().write("{\"error\":\"invalid\"}".getBytes(StandardCharsets.UTF_8));
                },f.db,f.p,()->{},f.files::transientCapacity);
                assertEquals(400,result.status.get());assertEquals(0,f.count("SELECT COUNT(*) FROM business"));assertEquals(0,f.confirms.get());
            }finally{if(previous==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",previous);Files.deleteIfExists(config);}
        }
    }
    @Test void rawPreparedAndEscapedStatementsCannotImplicitlyCommitOrChangeSessionState()throws Exception {
        try(Fixture f=new Fixture();AtomicDatabase.Scope scope=f.db.begin(UUID.randomUUID().toString())){
            Connection c=AtomicDatabase.borrowed(f.p);c.createStatement().executeUpdate("INSERT INTO business VALUES(3,'pending')");
            for(String sql:List.of("COMMIT","SET autocommit=1","XA COMMIT 'a'","CREATE TABLE bypass(id INT)","SELECT 1; COMMIT","/*!80000 COMMIT */ SELECT 1","SELECT GET_LOCK('outside',1)","SELECT 1 INTO OUTFILE '/tmp/no'")){
                assertThrows(SQLException.class,()->c.createStatement().execute(sql));assertThrows(SQLException.class,()->c.prepareStatement(sql));
            }
            try(PreparedStatement q=c.prepareStatement("SELECT '; SET autocommit=1'")){try(ResultSet r=q.executeQuery()){assertTrue(r.next());assertThrows(SQLException.class,()->r.getStatement().getConnection().createStatement().execute("COMMIT"));}}
            assertThrows(SQLException.class,()->c.prepareCall("CALL bypass()"));assertThrows(SQLException.class,()->c.getMetaData().unwrap(DatabaseMetaData.class));
            assertEquals(0,f.count("SELECT COUNT(*) FROM business"));
        }
    }
    @Test void failedLoginCommitLossCountsExactlyOnceAndReturnsKnownFailureAfterReplay()throws Exception {
        try(Fixture f=new Fixture()){
            Path config=Files.createTempFile("starhub-atomic-synthetic-", ".properties");String previous=System.getProperty("starhub.account.config");String op=UUID.randomUUID().toString(),password=UUID.randomUUID().toString();
            try{
                try(OutputStream out=Files.newOutputStream(config)){f.p.store(out,"Synthetic");}System.setProperty("starhub.account.config",config.toString());f.rejectConfirm.set(true);Capture unknown=new Capture();
                FilterChain login=(req,res)->new AccountServlet().doPost((HttpServletRequest)req,(HttpServletResponse)res);
                AtomicHttp.run(loginRequest(op,password),unknown.response,login,f.db,f.p,()->{},f.files::transientCapacity);assertEquals(503,unknown.status.get());assertEquals(1,f.count("SELECT failures FROM starhub_login_limits"));
                f.rejectConfirm.set(false);Capture known=new Capture();AtomicHttp.run(loginRequest(op,password),known.response,login,f.db,f.p,()->{},f.files::transientCapacity);
                assertEquals(401,known.status.get());assertTrue(known.bytes.toString(StandardCharsets.UTF_8).contains("\"operationState\":\"COMMITTED\""));assertEquals(1,f.count("SELECT failures FROM starhub_login_limits"));assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_login_attempts"));
            }finally{if(previous==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",previous);Files.deleteIfExists(config);}
        }
    }
    @Test void durableLoginThrottleAndReadOnlyStatusUseBoundedSharedCounters()throws Exception {
        try(Fixture f=new Fixture()){
            String first=UUID.randomUUID().toString();
            f.db.run(first,c->{var attempt=AtomicLoginThrottle.begin(f.p,first,"192.0.2.1","nobody@example.test","synthetic");AtomicLoginThrottle.complete(f.p,attempt,false);return null;});
            for(int i=1;i<8;i++){String op=UUID.randomUUID().toString();f.db.run(op,c->{var attempt=AtomicLoginThrottle.begin(f.p,op,"192.0.2.1","nobody@example.test","synthetic");assertEquals(0,attempt.priorStatus());AtomicLoginThrottle.complete(f.p,attempt,false);return null;});}
            String limited=UUID.randomUUID().toString();f.db.run(limited,c->{assertEquals(429,AtomicLoginThrottle.begin(f.p,limited,"192.0.2.1","nobody@example.test","synthetic").priorStatus());return null;});assertEquals(8,f.count("SELECT failures FROM starhub_login_limits"));
            for(int i=0;i<20;i++)f.db.run(UUID.randomUUID().toString(),c->{var known=AtomicLoginThrottle.status(f.p,first,"nobody@example.test","192.0.2.2");assertEquals(401,known.path("status").asInt());assertFalse(known.has("email"));return null;});
            f.db.run(UUID.randomUUID().toString(),c->{var failure=assertThrows(StorageFailure.class,()->AtomicLoginThrottle.status(f.p,first,"nobody@example.test","192.0.2.2"));assertEquals(429,failure.status);return null;});
            f.db.run(UUID.randomUUID().toString(),c->{assertEquals(404,assertThrows(StorageFailure.class,()->AtomicLoginThrottle.status(f.p,first,"different@example.test","192.0.2.3")).status);return null;});
        }
    }
    private static HttpServletRequest loginRequest(String op,String password)throws Exception {
        HttpServletRequest req=mock(HttpServletRequest.class);when(req.getMethod()).thenReturn("POST");when(req.getServletPath()).thenReturn("/api/account");when(req.getContentType()).thenReturn("application/json");when(req.getRemoteAddr()).thenReturn("192.0.2.10");
        when(req.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(req.getHeader("X-Starhub-Client")).thenReturn("web");
        byte[] bytes=("{\"action\":\"login\",\"email\":\"unknown@example.test\",\"password\":\""+password+"\",\"operationId\":\""+op+"\"}").getBytes(StandardCharsets.UTF_8);ByteArrayInputStream in=new ByteArrayInputStream(bytes);
        when(req.getInputStream()).thenReturn(new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener r){}});return req;
    }
    private static HttpServletRequest request(Fixture f,String operation)throws Exception {
        HttpSession session=mock(HttpSession.class);Map<String,Object> attrs=Map.of("starhub.account.id",1L,"starhub.account.email","user@example.test","starhub.account.generation",f.generation,"starhub.account.hash",f.accountHash);
        when(session.getAttribute(anyString())).thenAnswer(i->attrs.get(i.getArgument(0)));when(session.getAttributeNames()).thenAnswer(i->Collections.enumeration(attrs.keySet()));
        HttpServletRequest req=mock(HttpServletRequest.class);when(req.getMethod()).thenReturn("POST");when(req.getServletPath()).thenReturn("/api/workspace");when(req.getContentType()).thenReturn("application/json");
        when(req.getHeader("Origin")).thenReturn("https://starhub.zhongjiehuang.com");when(req.getHeader("X-Starhub-Client")).thenReturn("web");when(req.getSession(anyBoolean())).thenReturn(session);
        byte[] bytes=("{\"action\":\"saveChat\",\"expectedEmail\":\"user@example.test\",\"operationId\":\""+operation+"\"}").getBytes(StandardCharsets.UTF_8);ByteArrayInputStream in=new ByteArrayInputStream(bytes);
        when(req.getInputStream()).thenReturn(new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener r){}});return req;
    }
    static final class Capture {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();final AtomicInteger status=new AtomicInteger(200);final HttpServletResponse response=mock(HttpServletResponse.class);
        Capture()throws Exception {
            doAnswer(i->{status.set(i.getArgument(0));return null;}).when(response).setStatus(anyInt());when(response.isCommitted()).thenReturn(false);
            doAnswer(i->{bytes.reset();return null;}).when(response).resetBuffer();when(response.getOutputStream()).thenReturn(new ServletOutputStream(){public void write(int v){bytes.write(v);}public boolean isReady(){return true;}public void setWriteListener(WriteListener l){}});
        }
    }
}
