package com.starhub;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import jakarta.servlet.http.HttpServletRequest;
import javax.sql.*;
import javax.transaction.xa.*;

/** Explicit opt-in harness. Refuses every database except isolated starhub_storage_test_* schemas. */
public final class MySqlStorageValidation {
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("private-properties schema-directory initialize|basic|random-integrity|rollback|quota-reservation|capacity-fill|crash|recover");
        String phase=args[2];
        Properties config=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){config.load(in);}
        for(String key:List.of("db.url","storage.node2.db.url"))if(!config.getProperty(key,"").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/starhub_storage_test_[a-z0-9_]+\\?.*"))throw new IllegalArgumentException("Only isolated validation databases are permitted.");
        if("jdbc".equals(config.getProperty("storage.objectStore.backend"))&&!config.getProperty("storage.objectStore.jdbc.url","").matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/starhub_storage_test_[a-z0-9_]+\\?.*"))throw new IllegalArgumentException("Only an isolated validation decision store is permitted.");
        if(phase.equals("capacity-fill")&&!"true".equals(config.getProperty("storage.replicated")))throw new IllegalArgumentException("capacity-fill requires validated replication and real authenticated observers; simulated probes are forbidden.");
        Class.forName("com.mysql.cj.jdbc.Driver");
        List<String> prefixes=new ArrayList<>(List.of("db.","storage.node2.db."));
        if("jdbc".equals(config.getProperty("storage.objectStore.backend")))prefixes.add("storage.objectStore.jdbc.");
        for(String prefix:prefixes)try(Connection c=DriverManager.getConnection(config.getProperty(prefix+"url"),config.getProperty(prefix+"user"),config.getProperty(prefix+"password"));Statement q=c.createStatement();ResultSet row=q.executeQuery("SELECT DATABASE()")) {
            check(row.next()&&row.getString(1)!=null&&row.getString(1).matches("starhub_storage_test_[a-z0-9_]+"),"the connected database itself must be isolated");
            String expected=config.getProperty("validation.expectedMysqlVersion","26.10.0");
            check(c.getMetaData().getDatabaseProductVersion().startsWith(expected),"connected MySQL must match the explicitly expected current test version");
            System.out.println("DATABASE_TEST_VERSION role="+prefix+" mysql="+c.getMetaData().getDatabaseProductVersion()+" jdbc="+c.getMetaData().getDriverVersion());
        }
        Path state=Path.of(args[0]).toAbsolutePath().getParent().resolve("crash-operation.txt");
        if(phase.equals("basic")||phase.equals("initialize")){
            try(Connection c=AccountServlet.db(config);Statement q=c.createStatement()){
                q.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,active BOOLEAN NOT NULL DEFAULT TRUE,generation CHAR(36) NOT NULL DEFAULT(UUID())) ENGINE=InnoDB");
                q.execute("INSERT INTO users(id) VALUES(1),(2)");
                script(c,Path.of(args[1],"001_workspace.sql"));script(c,Path.of(args[1],"003_file_pagination.sql"));script(c,Path.of(args[1],"004_distributed_file_storage.sql"));script(c,Path.of(args[1],"005_file_blob_node.sql"));
            }
            try(Connection c=DriverManager.getConnection(config.getProperty("storage.node2.db.url"),config.getProperty("storage.node2.db.user"),config.getProperty("storage.node2.db.password"))){script(c,Path.of(args[1],"005_file_blob_node.sql"));}
        }
        if(phase.equals("upgrade")){
            try(Connection c=AccountServlet.db(config)){script(c,Path.of(args[1],"004_distributed_file_storage.sql"));}
            provisionIdentity(config);System.out.println("PASS: isolated validation schema upgraded.");return;
        }
        if(phase.equals("basic")||phase.equals("initialize")) {
            provisionIdentity(config);
            if("jdbc".equals(config.getProperty("storage.objectStore.backend"))) {
                String marker=Files.readString(Path.of(config.getProperty("storage.objectStore"),"identity")).trim();
                try(Connection c=DriverManager.getConnection(config.getProperty("storage.objectStore.jdbc.url"),config.getProperty("storage.objectStore.jdbc.user"),config.getProperty("storage.objectStore.jdbc.password"));PreparedStatement q=c.prepareStatement("INSERT INTO starhub_decision_store_identity(ID,STORE_UUID) VALUES(1,?)")) {q.setString(1,marker);q.executeUpdate();}
            }
        }
        if(phase.equals("initialize")){System.out.println("PASS: isolated storage schemas initialized.");return;}
        boolean multiCapacity=phase.equals("multi-capacity");
        if(multiCapacity){
            if(!"true".equals(config.getProperty("storage.replicated")))throw new IllegalArgumentException("Replicated validation required");
            try(Connection c=DriverManager.getConnection(config.getProperty("db.url"),config.getProperty("db.user"),config.getProperty("db.password"))){script(c,Path.of(args[1],"006_physical_replica_reservations.sql"));}
            config.setProperty("storage.physicalNodeIds","1,2,10");
        }
        AtomicLong freeOne=new AtomicLong(50_000_000_000L),freeTwo=new AtomicLong(50_000_000_000L),freeTen=new AtomicLong(50_000_000_000L);
        AtomicBoolean healthy=new AtomicBoolean(true),peerDown=new AtomicBoolean(false),singleAuthorized=new AtomicBoolean(false);
        // Real probes are opt-in and remain restricted to the isolated test
        // databases validated above; all other phases keep their fake probes.
        boolean realProbes=phase.equals("real-probes")||phase.equals("capacity-fill");
        HttpServer probes=realProbes?null:HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        if(!realProbes){
        if(config.getProperty("storage.probe.token","").length()<32)
            config.setProperty("storage.probe.token","validation-only-replication-token-not-for-production");
        String testMysqlUuid;
        try(Connection c=DriverManager.getConnection(config.getProperty("db.url"),config.getProperty("db.user"),config.getProperty("db.password"));
            Statement q=c.createStatement();ResultSet rows=q.executeQuery("SELECT @@server_uuid")){if(!rows.next())throw new SQLException("missing test MySQL identity");testMysqlUuid=rows.getString(1);}
        probes.createContext("/authority",exchange->{
            if(!Objects.equals(exchange.getRequestHeaders().getFirst("Authorization"),"Bearer "+config.getProperty("storage.probe.token"))){exchange.sendResponseHeaders(403,-1);exchange.close();return;}
            if(!healthy.get()||(peerDown.get()&&!singleAuthorized.get())){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
            exchange.getResponseHeaders().set("X-Four-Project-MySQL-UUID",testMysqlUuid);exchange.sendResponseHeaders(204,-1);exchange.close();
        });
        probes.createContext("/status",exchange->{
            if(!Objects.equals(exchange.getRequestHeaders().getFirst("Authorization"),"Bearer "+config.getProperty("storage.probe.token"))){exchange.sendResponseHeaders(403,-1);exchange.close();return;}
            int node=Integer.parseInt(exchange.getRequestURI().getQuery().substring(5));
            if(node==1&&peerDown.get()){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
            var body=ModelGateway.JSON.createObjectNode().put("nodeId",node).put("observedAtMillis",System.currentTimeMillis())
                    .put("mysqlReady",healthy.get()).put("tomcatReady",healthy.get()).put("reserveGuaranteed",true)
                    .put("singleNodeAuthorized",node==2&&singleAuthorized.get())
                    .put("replicationMode",multiCapacity?"quorum":node==2&&singleAuthorized.get()?"single":"dual")
                    .put("physicalBytesPerPayloadByte",2).put("fixedOverheadBytes",1024);
            if(multiCapacity){body.put("viewId","synthetic-view").put("membershipDigest","a".repeat(64)).put("memberUuid","synthetic-member-"+node);body.putArray("onlineNodeIds").add(1).add(2).add(10);body.putArray("configuredNodeIds").add(1).add(2).add(10);}
            body.putArray("volumes").addObject().put("id","TEST-SIMULATED-CAPACITY").put("availableBytes",node==1?freeOne.get():node==2?freeTwo.get():freeTen.get());
            byte[] bytes=ModelGateway.JSON.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });probes.start();
        for(int node:new int[]{1,2})config.setProperty("storage.node"+node+".probe.url","http://127.0.0.1:"+probes.getAddress().getPort()+"/status?node="+node);
        if(multiCapacity)config.setProperty("storage.node10.probe.url","http://127.0.0.1:"+probes.getAddress().getPort()+"/status?node=10");
        config.setProperty("storage.authority.url","http://127.0.0.1:"+probes.getAddress().getPort()+"/authority");
        }
        AtomicBoolean journalFault=new AtomicBoolean(phase.equals("journal-fault"));
        AtomicBoolean replicationLoss=new AtomicBoolean(phase.equals("replication-loss-after-commit"));
        FileStorage service=new FileStorage(config){
            @Override public void ended(int node,Xid xid,String terminal)throws Exception {
                if(node==2&&"COMMITTED".equals(terminal)&&journalFault.compareAndSet(true,false))throw new SQLException("Injected post-commit journal outage","40001");
                super.ended(node,xid,terminal);
                if(node==2&&"COMMITTED".equals(terminal)&&replicationLoss.compareAndSet(true,false))healthy.set(false);
            }
        };FileStorage.active=service;
        try{
            awaitReady(service);
            int ingress=Integer.parseInt(config.getProperty("validation.ingress",realProbes?"1":"2"));
            if(ingress!=1&&ingress!=2)throw new IllegalArgumentException("Invalid validation ingress");
            HttpServletRequest request=request(config,ingress);
            byte[] content="stored-on-node-two".getBytes(StandardCharsets.UTF_8);
            if(phase.equals("random-integrity")){
                randomIntegrity(service,config);
            }else if(phase.equals("rollback")){
                rollback(service,request(config,2));
            }else if(phase.equals("quota-reservation")){
                quotaReservation(service,request);
            }else if(phase.equals("capacity-fill")){
                capacityFill(service,config);
            }else if(phase.equals("basic")){
                String operation=UUID.randomUUID().toString();var saved=service.upload(request,1,operation,List.of(new FileStorage.Upload("a.txt","text/plain",content)));String file=saved.path("id").asText();
                check(count(service,1,"SELECT COUNT(*) FROM library_files WHERE storage_node_id=2")==1,"directory points to node two");
                check(count(service,2,"SELECT COUNT(*) FROM file_blobs")==1,"body exists on node two");
                check(count(service,1,"SELECT COUNT(*) FROM file_blobs")==0,"no duplicate body on node one");
                check(service.read(1,file).path("data").asText().equals(Base64.getEncoder().encodeToString(content)),"content round trip");
                check(service.upload(request,1,operation,List.of(new FileStorage.Upload("a.txt","text/plain",content))).path("id").asText().equals(file),"retry returns original identity");
                check(count(service,2,"SELECT COUNT(*) FROM file_blobs")==1,"idempotent retry does not insert");
                expect(409,()->service.upload(request,1,operation,List.of(new FileStorage.Upload("different.txt","text/plain",content))));
                try(Connection c=service.primary();Statement q=c.createStatement()){q.executeUpdate("UPDATE file_storage_users SET used_bytes=1999999999 WHERE user_id=1");}
                expect(413,()->service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("too-big.txt","text/plain",content))));
                try(Connection c=service.primary();Statement q=c.createStatement()){q.executeUpdate("UPDATE file_storage_users SET used_bytes="+content.length+" WHERE user_id=1");}
                freeTwo.set(StorageCluster.FLOOR);var fallback=service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("fallback.txt","text/plain",new byte[]{1})));
                check(count(service,1,"SELECT COUNT(*) FROM file_blobs")==1,"full preferred node is skipped before admission");
                freeOne.set(StorageCluster.FLOOR);expect(507,()->service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("no-room.txt","text/plain",new byte[]{1}))));
                freeOne.set(50_000_000_000L);freeTwo.set(50_000_000_000L);
                healthy.set(false);expect(503,()->service.read(1,file));healthy.set(true);awaitReady(service);
                service.trash(1,file,false);expect(404,()->service.read(1,file));service.trash(1,file,true);service.read(1,file);service.trash(1,file,false);
                String deletion=UUID.randomUUID().toString();service.delete(1,deletion,file);service.delete(1,deletion,file);
                check(count(service,2,"SELECT COUNT(*) FROM file_blobs")==0,"global deletion removes remote body");
                check(count(service,1,"SELECT reserved_bytes FROM file_storage_users WHERE user_id=1")==0,"quota reservations released once");
                check(count(service,1,"SELECT SUM(reserved_bytes) FROM file_storage_nodes")==0,"node reservations released once");
                System.out.println("PASS: placement, content integrity, idempotency/conflict, quota, fallback, full disks, node failure, trash/restore and global deletion.");
            }else if(phase.equals("journal-fault")){
                String operation=UUID.randomUUID().toString();
                try{service.upload(request,1,operation,List.of(new FileStorage.Upload("journal.txt","text/plain",content)));}
                catch(StorageFailure failure){check(failure.status==503,"uncertain journal state must remain unavailable");}
                check(!journalFault.get(),"post-commit journal failure was injected");
                awaitReady(service);
                var result=service.status(1,operation);check("COMMITTED".equals(result.path("state").asText()),"committed operation recovered");
                String file=result.path("id").asText();
                check(service.read(1,file).path("data").asText().equals(Base64.getEncoder().encodeToString(content)),"recovered file bytes match the interrupted upload");
                check(service.upload(request,1,operation,List.of(new FileStorage.Upload("journal.txt","text/plain",content))).path("id").asText().equals(file),"retry did not duplicate the upload");
                check(!service.transactions.hasOutstandingDecisions(),"journal failure must not create a heuristic outcome");
                System.out.println("PASS: post-commit journal failure retried without a false rollback or duplicate upload.");
            }else if(phase.equals("recovery-during-upload")){
                var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
                service.sources.put(2,pauseAfterPrepare(service.sources.get(2),entered,release));
                var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    var future=executor.submit(()->service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("in-flight.txt","text/plain",content))));
                    check(entered.await(30,java.util.concurrent.TimeUnit.SECONDS),"upload reached prepare");
                    service.recover();check(service.ready,"normal in-flight work must not cause a false outage");
                    release.countDown();check(future.get(60,java.util.concurrent.TimeUnit.SECONDS).path("ok").asBoolean(),"upload committed");
                }finally{release.countDown();executor.shutdownNow();}
                System.out.println("PASS: recovery scan preserved readiness during a live upload.");
            }else if(phase.equals("replicated-admission")){
                check(service.replicated,"replicated mode must be enabled");
                var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
                service.sources.put(2,pauseAfterPrepare(service.sources.get(2),entered,release));
                var pool=java.util.concurrent.Executors.newSingleThreadExecutor();String later=UUID.randomUUID().toString();
                try{
                    var first=pool.submit(()->service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("first.txt","text/plain",content))));
                    check(entered.await(30,java.util.concurrent.TimeUnit.SECONDS),"first upload reached prepare");
                    expect(503,()->service.upload(request,1,later,List.of(new FileStorage.Upload("later.txt","text/plain",content))));
                    release.countDown();first.get(60,java.util.concurrent.TimeUnit.SECONDS);
                    var saved=service.upload(request,1,later,List.of(new FileStorage.Upload("later.txt","text/plain",content)));
                    check(service.read(1,saved.path("id").asText()).path("data").asText().equals(Base64.getEncoder().encodeToString(content)),"rejected concurrent upload can safely retry");
                }finally{release.countDown();pool.shutdownNow();}
                System.out.println("PASS: replicated file operations admit one request at a time and allow safe retry.");
            }else if(phase.equals("real-probes")){
                check(service.replicated,"replicated mode must be enabled");
                String operation=UUID.randomUUID().toString();
                var saved=service.upload(request,1,operation,List.of(new FileStorage.Upload("real-probe.txt","text/plain",content)));
                String id=saved.path("id").asText();
                check(service.read(1,id).path("data").asText().equals(Base64.getEncoder().encodeToString(content)),"file round trip with actual observers");
                check(service.upload(request,1,operation,List.of(new FileStorage.Upload("real-probe.txt","text/plain",content))).path("id").asText().equals(id),"real-probe retry remains idempotent");
                check(count(service,1,"SELECT SUM(reserved_bytes) FROM file_storage_nodes")==0,"capacity reservations released");
                System.out.println("PASS: isolated replicated file round trip used the supplied authenticated observers.");
            }else if(phase.equals("large-replicated")){
                check(service.replicated,"replicated mode must be enabled");
                byte[] large=new byte[GeneratedImages.MAX_IMAGE];new java.util.Random(42).nextBytes(large);
                var saved=service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("large.png","image/png",large)));
                byte[] actual=Base64.getDecoder().decode(service.read(1,saved.path("id").asText()).path("data").asText());
                check(java.security.MessageDigest.isEqual(FileStorage.hash(large),FileStorage.hash(actual)),"maximum-sized generated image round trip");
                System.out.println("PASS: 12 MiB replicated file upload and read have matching bytes.");
            }else if(phase.equals("replication-loss-after-commit")){
                check(service.replicated,"replicated mode must be enabled");
                String operation=UUID.randomUUID().toString();
                expect(503,()->service.upload(request,1,operation,List.of(new FileStorage.Upload("uncertain.txt","text/plain",content))));
                healthy.set(true);awaitReady(service);
                var saved=service.upload(request,1,operation,List.of(new FileStorage.Upload("uncertain.txt","text/plain",content)));
                check(service.read(1,saved.path("id").asText()).path("data").asText().equals(Base64.getEncoder().encodeToString(content)),"post-commit retry preserves file bytes");
                check(count(service,2,"SELECT COUNT(*) FROM file_blobs WHERE id='"+saved.path("id").asText()+"'")==1,"post-commit retry does not duplicate body");
                System.out.println("PASS: loss of replicated readiness after commit returns uncertainty; the same operation replays once.");
            }else if(phase.equals("replicated-degraded")){
                check(service.replicated,"replicated mode must be enabled");
                peerDown.set(true);
                expect(503,()->service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("no-fence.txt","text/plain",content))));
                singleAuthorized.set(true);
                var result=service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("fenced.txt","text/plain",content)));
                check(service.read(1,result.path("id").asText()).path("data").asText().equals(Base64.getEncoder().encodeToString(content)),"fenced survivor serves uploaded body");
                check(count(service,1,"SELECT SUM(reserved_bytes) FROM file_storage_nodes")==0,"single-node reservation released");
                System.out.println("PASS: simulated peer loss fails closed until the local agent attests fencing.");
            }else if(multiCapacity){
                freeTen.set(StorageCluster.FLOOR);
                expect(507,()->service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("full.txt","text/plain",content))));
                check(count(service,1,"SELECT COALESCE(SUM(reserved_bytes),0) FROM file_storage_physical_nodes")==0,"rejected upload has no leaked physical reservations");
                freeTen.set(50_000_000_000L);var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
                service.sources.put(2,pauseAfterPrepare(service.sources.get(2),entered,release));
                var pool=java.util.concurrent.Executors.newSingleThreadExecutor();String operation=UUID.randomUUID().toString();
                try{
                    var future=pool.submit(()->service.upload(request,1,operation,List.of(new FileStorage.Upload("multi.txt","text/plain",content))));
                    check(entered.await(30,java.util.concurrent.TimeUnit.SECONDS),"test upload reached prepare");
                    check(count(service,1,"SELECT COUNT(*) FROM file_storage_replica_reservations WHERE reserved_bytes>0")==3,"all three physical replicas are reserved");
                    check(count(service,1,"SELECT COUNT(*) FROM file_storage_physical_nodes WHERE reserved_bytes>0")==3,"physical counters are reserved");
                    release.countDown();var saved=future.get(60,java.util.concurrent.TimeUnit.SECONDS);
                    check(service.read(1,saved.path("id").asText()).path("data").asText().equals(Base64.getEncoder().encodeToString(content)),"file content survives reservation release");
                    check(count(service,1,"SELECT COALESCE(SUM(reserved_bytes),0) FROM file_storage_physical_nodes")==0,"all physical reservations released");
                    check(count(service,1,"SELECT COUNT(*) FROM file_storage_replica_reservations")==0,"reservation records released exactly once");
                }finally{release.countDown();pool.shutdownNow();}
                System.out.println("PASS: three synthetic capacity observers reserve and release against isolated real MySQL transactions.");
            }else if(phase.equals("replicated-capacity")){
                check(service.replicated,"replicated mode must be enabled for this test");
                freeTwo.set(StorageCluster.FLOOR);
                expect(507,()->service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("replica-full.txt","text/plain",content))));
                check(count(service,1,"SELECT SUM(reserved_bytes) FROM file_storage_nodes")==0,"rejected replica upload does not reserve space");
                freeTwo.set(50_000_000_000L);
                service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("replica-ok.txt","text/plain",content)));
                check(count(service,1,"SELECT SUM(reserved_bytes) FROM file_storage_nodes")==0,"both physical reservations released");
                System.out.println("PASS: replicated upload requires and releases capacity on both physical replicas.");
            }else if(phase.equals("concurrent")){
                try(Connection c=service.primary();Statement q=c.createStatement()){q.executeUpdate("UPDATE file_storage_users SET used_bytes=1999999999,reserved_bytes=0 WHERE user_id=2");}
                var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
                try{
                    List<java.util.concurrent.Future<Integer>> attempts=new ArrayList<>();
                    for(int i=0;i<2;i++)attempts.add(pool.submit(()->{start.await();try{service.upload(request,2,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("quota-race.txt","text/plain",new byte[]{1})));return 200;}catch(StorageFailure failure){return failure.status;}}));
                    start.countDown();int success=0;for(var future:attempts){int status=future.get();if(status==200)success++;else check(status==413||status==503,"quota race safely rejects second request");}
                    check(success==1,"only one concurrent upload consumes final quota byte");
                }finally{pool.shutdownNow();}
                check(count(service,1,"SELECT used_bytes FROM file_storage_users WHERE user_id=2")==2_000_000_000L,"quota cannot overshoot");
                try(Connection c=service.primary();Statement q=c.createStatement()){q.executeUpdate("UPDATE file_storage_users SET used_bytes=(SELECT COALESCE(SUM(size_bytes),0) FROM library_files WHERE user_id=2) WHERE user_id=2");}
                freeOne.set(StorageCluster.FLOOR+1025);
                expect(507,()->service.upload(request,2,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("control-full.txt","text/plain",new byte[]{1}))));
                freeOne.set(StorageCluster.FLOOR+1026);
                service.upload(request,2,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("control-boundary.txt","text/plain",new byte[]{1})));
                check(count(service,1,"SELECT SUM(reserved_bytes) FROM file_storage_nodes")==0,"both target and control reservations released");
                System.out.println("PASS: concurrent requests cannot exceed 2 GB; remote body placement checks primary metadata headroom and exact4GB boundary.");
            }else if(phase.equals("crash")||phase.equals("crash-after-commit")||phase.equals("crash-after-prepare")){
                String operation=UUID.randomUUID().toString();Files.writeString(state,operation);
                if(phase.equals("crash-after-commit"))for(int node:new int[]{1,2})service.sources.put(node,haltAt(service.sources.get(node),"commit",true));
                else if(phase.equals("crash-after-prepare"))for(int node:new int[]{1,2})service.sources.put(node,haltAt(service.sources.get(node),"prepare",true));
                else service.sources.put(2,haltAt(service.sources.get(2),"commit",false));
                service.upload(request,1,operation,List.of(new FileStorage.Upload("crash.txt","text/plain",content)));
                throw new AssertionError("Crash injection was not reached.");
            }else if(phase.equals("recover-abort")){
                String operation=Files.readString(state).trim();var result=service.status(1,operation);
                check(result.path("state").asText().equals("ABORTED"),"pre-decision crash must resolve as abort");
                String file=result.path("id").asText();expect(404,()->service.read(1,file));
                expect(409,()->service.upload(request,1,operation,List.of(new FileStorage.Upload("crash.txt","text/plain",content))));
                check(count(service,1,"SELECT COUNT(*) FROM file_storage_users WHERE active_op IS NOT NULL")==0,"aborted gates released");
                check(count(service,1,"SELECT SUM(reserved_bytes) FROM file_storage_nodes")==0,"aborted physical reservations released");
                check(count(service,1,"SELECT SUM(reserved_bytes) FROM file_storage_users")==0,"aborted quota reservations released");
                check(!service.transactions.hasOutstandingDecisions(),"aborted decision logs resolved");
                System.out.println("PASS: process death after prepare and before global decision safely aborted, with no published file or leaked reservation.");
            }else if(phase.equals("recover")){
                String operation=Files.readString(state).trim();var result=service.status(1,operation);
                check(result.path("state").asText().equals("COMMITTED"),"durable decision completed after process crash");
                String file=result.path("id").asText();
                check(service.read(1,file).path("data").asText().equals(Base64.getEncoder().encodeToString(content)),"recovered file bytes match the interrupted upload");
                check(service.upload(request,1,operation,List.of(new FileStorage.Upload("crash.txt","text/plain",content))).path("id").asText().equals(file),"post-crash retry is idempotent");
                check(count(service,1,"SELECT COUNT(*) FROM file_storage_users WHERE active_op IS NOT NULL")==0,"gates released after recovery");
                check(count(service,1,"SELECT SUM(reserved_bytes) FROM file_storage_nodes")==0,"capacity reservations recovered");
                check(!service.transactions.hasUnresolvedDecisionLogs(),"durable recovery decisions fully resolved");
                System.out.println("PASS: abrupt JVM exit in phase two recovered both MySQL branches, released gates, and preserved idempotency.");
            }else throw new IllegalArgumentException("Unknown validation phase.");
        }finally{service.close();FileStorage.active=null;if(probes!=null)probes.stop(0);}
    }
    /** Uses actual JDBC transactions and incompressible bytes, never provider APIs. */
    private static void randomIntegrity(FileStorage service,Properties config)throws Exception {
        for(int user:new int[]{1,2}) {
            byte[] bytes=new byte[WorkspaceServlet.FILE_MAX];new Random(0x51A7B00L+user).nextBytes(bytes);
            String operation=UUID.randomUUID().toString();HttpServletRequest entrance=request(config,user);
            long before=count(service,1,"SELECT COALESCE(SUM(size_bytes),0) FROM library_files WHERE user_id="+user);
            var saved=service.upload(entrance,user,operation,List.of(new FileStorage.Upload("random-"+user+".txt","text/plain",bytes)));
            String id=saved.path("id").asText();verifyBytes(service,user,id,bytes);
            check(service.upload(entrance,user,operation,List.of(new FileStorage.Upload("random-"+user+".txt","text/plain",bytes))).path("id").asText().equals(id),"random-content retry returns the same file");
            check(count(service,1,"SELECT COUNT(*) FROM library_files WHERE id='"+id+"'")==1,"retry creates one catalogue row");
            final int owner=user,other=3-user;expect(404,()->service.read(other,id));
            byte[] different=bytes.clone();different[0]^=1;
            expect(409,()->service.upload(entrance,owner,operation,List.of(new FileStorage.Upload("random-"+owner+".txt","text/plain",different))));
            service.trash(user,id,false);expect(404,()->service.read(owner,id));
            check(count(service,1,"SELECT used_bytes FROM file_storage_users WHERE user_id="+user)==before+bytes.length,"trash remains charged to quota");
            service.trash(user,id,true);verifyBytes(service,user,id,bytes);service.trash(user,id,false);
            String deletion=UUID.randomUUID().toString();service.delete(user,deletion,id);service.delete(user,deletion,id);
            check(count(service,1,"SELECT used_bytes FROM file_storage_users WHERE user_id="+user)==before,"permanent delete releases the logical bytes once");
            check(count(service,user,"SELECT COUNT(*) FROM file_blobs WHERE id='"+id+"'")==0,"permanent delete removes the full blob on its logical node");
            check("COMMITTED".equals(service.status(user,deletion).path("state").asText()),"delete outcome is queryable");
        }
        assertReleased(service);
        System.out.println("PASS: two users, both ingress identities, 4 MiB random bytes/SHA256, account isolation, trash/restore, idempotency/conflict and permanent deletion.");
    }

    private static void verifyBytes(FileStorage service,long user,String id,byte[] expected)throws Exception {
        byte[] actual=Base64.getDecoder().decode(service.read(user,id).path("data").asText());
        check(Arrays.equals(expected,actual),"read returns every original random byte");
        int node=(int)count(service,1,"SELECT storage_node_id FROM library_files WHERE id='"+id+"'");
        try(Connection c=service.node(node);PreparedStatement q=c.prepareStatement("SELECT content,sha256,size_bytes FROM file_blobs WHERE id=?")) {
            q.setString(1,id);try(ResultSet rows=q.executeQuery()) {
                check(rows.next(),"blob exists on recorded logical node");byte[] stored=rows.getBytes(1);
                check(Arrays.equals(expected,stored)&&expected.length==rows.getLong(3),"stored body and size match all submitted bytes");
                check(java.security.MessageDigest.isEqual(FileStorage.hash(expected),rows.getBytes(2)),"stored SHA256 matches original random bytes");
            }
        }
    }

    /** Fails metadata after the remote blob INSERT, before prepare, then checks real XA rollback. */
    private static void rollback(FileStorage service,HttpServletRequest request)throws Exception {
        byte[] bytes=new byte[64*1024];new Random(82731).nextBytes(bytes);
        long before=count(service,1,"SELECT COALESCE(SUM(size_bytes),0) FROM library_files WHERE user_id=1");
        String operation=UUID.randomUUID().toString();AtomicBoolean injected=new AtomicBoolean();
        XADataSource original=service.sources.get(1);service.sources.put(1,failMetadataOnce(original,injected));
        try {
            expect(503,()->service.upload(request,1,operation,List.of(new FileStorage.Upload("rollback.txt","text/plain",bytes))));
            check(injected.get(),"metadata failure was injected after the blob INSERT");
        }finally{service.sources.put(1,original);}
        awaitReady(service);var outcome=service.status(1,operation);
        check("ABORTED".equals(outcome.path("state").asText()),"failed transaction has a durable ABORTED result");
        String id=outcome.path("id").asText();expect(404,()->service.read(1,id));
        for(int node:new int[]{1,2}) {
            check(count(service,node,"SELECT COUNT(*) FROM file_blobs WHERE id='"+id+"'")==0,"rolled-back body does not remain on either logical node");
            check(count(service,node,"SELECT COUNT(*) FROM file_storage_receipts WHERE op_id='"+operation+"'")==0,"rollback publishes no commit receipt");
        }
        check(count(service,1,"SELECT used_bytes FROM file_storage_users WHERE user_id=1")==before,"rollback does not consume quota");
        expect(409,()->service.upload(request,1,operation,List.of(new FileStorage.Upload("rollback.txt","text/plain",bytes))));
        var retried=service.upload(request,1,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("rollback.txt","text/plain",bytes)));
        verifyBytes(service,1,retried.path("id").asText(),bytes);assertReleased(service);
        System.out.println("PASS: a failure after the body INSERT rolls back real JDBC/XA work, preserves ABORTED result, releases reservations and permits a fresh operation.");
    }

    private static void assertReleased(FileStorage service)throws Exception {
        check(count(service,1,"SELECT COALESCE(SUM(reserved_bytes),0) FROM file_storage_users")==0,"no leaked logical reservation");
        check(count(service,1,"SELECT COUNT(*) FROM file_storage_users WHERE active_op IS NOT NULL")==0,"no leaked user gate");
        check(count(service,1,"SELECT COALESCE(SUM(reserved_bytes),0) FROM file_storage_nodes")==0,"no leaked physical reservation");
        check(!service.transactions.hasOutstandingDecisions(),"no unresolved durable decision");
    }

    private static void quotaReservation(FileStorage service,HttpServletRequest request)throws Exception {
        byte[] bytes=new byte[WorkspaceServlet.FILE_MAX];new Random(7753).nextBytes(bytes);
        // Synthetic accounting establishes the quota boundary without allocating 2 GB.
        // The separate capacity-fill phase alone measures real near-quota storage.
        try(Connection c=service.primary();Statement q=c.createStatement()) {
            q.executeUpdate("UPDATE file_storage_users SET used_bytes="+(WorkspaceServlet.QUOTA-bytes.length)+",reserved_bytes=0 WHERE user_id=2 AND active_op IS NULL");
        }
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        int target=Integer.parseInt(request.getHeader("X-Starhub-Ingress-Node"));XADataSource original=service.sources.get(target);
        service.sources.put(target,pauseAfterPrepare(original,entered,release));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);String operation=UUID.randomUUID().toString();
        try {
            var first=executor.submit(()->service.upload(request,2,operation,List.of(new FileStorage.Upload("reserved.txt","text/plain",bytes))));
            check(entered.await(30,java.util.concurrent.TimeUnit.SECONDS),"first upload reached prepare while quota remains reserved");
            check(count(service,1,"SELECT reserved_bytes FROM file_storage_users WHERE user_id=2")==bytes.length,"pending bytes are visibly reserved before commit");
            var attempted=new java.util.concurrent.CountDownLatch(1);
            var competing=executor.submit(()->{attempted.countDown();try {service.upload(request,2,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("competing.txt","text/plain",new byte[]{1})));return 200;}catch(StorageFailure failure){return failure.status;}});
            check(attempted.await(5,java.util.concurrent.TimeUnit.SECONDS),"competing request started");
            release.countDown();var saved=first.get(60,java.util.concurrent.TimeUnit.SECONDS);
            int result=competing.get(60,java.util.concurrent.TimeUnit.SECONDS);
            check(result==413||result==503,"concurrent admission must reject/pause rather than exceed quota");
            check(count(service,1,"SELECT used_bytes FROM file_storage_users WHERE user_id=2")==WorkspaceServlet.QUOTA,"committed accounting reaches exactly 2 GB");
            verifyBytes(service,2,saved.path("id").asText(),bytes);assertReleased(service);
            try(Connection c=service.primary();Statement q=c.createStatement()) {
                q.executeUpdate("UPDATE file_storage_users SET used_bytes=(SELECT COALESCE(SUM(size_bytes),0) FROM library_files WHERE user_id=2) WHERE user_id=2 AND active_op IS NULL");
            }
        }finally{release.countDown();executor.shutdownNow();service.sources.put(target,original);}
        System.out.println("PASS: a real in-flight 4 MiB upload reserves quota; a competing request cannot exceed a synthetically seeded 2 GB accounting boundary. This is not a physical capacity test.");
    }

    /** Only supplied, authenticated REAL observers may authorize this high-volume phase. */
    private static void capacityFill(FileStorage service,Properties config)throws Exception {
        check(service.replicated,"capacity-fill requires validated replicated mode and real capacity observers");
        String run=config.getProperty("validation.capacity.runId","");UUID.fromString(run);
        Path volume=Path.of(config.getProperty("validation.capacity.volumePath",""));
        check(volume.isAbsolute()&&Files.isDirectory(volume),"configure the actual local MySQL volume path");
        FileStore disk=Files.getFileStore(volume);long initial=disk.getUnallocatedSpace();
        check(initial>=StorageCluster.FLOOR,"actual free blocks are already below the 4 GB floor");
        check(count(service,1,"SELECT COUNT(*) FROM library_files WHERE user_id IN (1,2)")==0,"use fresh, dedicated capacity test schemas");
        for(int user:new int[]{1,2})check(count(service,1,"SELECT used_bytes+reserved_bytes FROM file_storage_users WHERE user_id="+user)==0,"capacity-test users start with no charged or reserved bytes");
        long[] written={0,0};String[] last={null,null};long sequence=0;
        try {
            while(written[0]<WorkspaceServlet.QUOTA||written[1]<WorkspaceServlet.QUOTA) {
                for(int user:new int[]{1,2}) {
                    int index=user-1;if(written[index]==WorkspaceServlet.QUOTA)continue;
                    int length=(int)Math.min(WorkspaceServlet.FILE_MAX,WorkspaceServlet.QUOTA-written[index]);
                    byte[] bytes=new byte[length];new Random(0x2B000000L+sequence*31+user).nextBytes(bytes);
                    var observations=service.cluster.probes();
                    for(var probe:observations.values())check(probe.admits(length,0,System.currentTimeMillis()),"actual observers must admit this bounded upload above 4 GB");
                    long budget=observations.get(Integer.parseInt(config.getProperty("server.id"))).budget(length);
                    long free=disk.getUnallocatedSpace();
                    if(free<StorageCluster.FLOOR||budget>free-StorageCluster.FLOOR)throw new StorageFailure(507,"INSUFFICIENT_STORAGE","Capacity test safely stopped before the 4 GB floor.");
                    String op=UUID.nameUUIDFromBytes((run+":"+user+":"+sequence).getBytes(StandardCharsets.UTF_8)).toString();
                    var saved=service.upload(request(config,user),user,op,List.of(new FileStorage.Upload("capacity-"+sequence+".txt","text/plain",bytes)));
                    last[index]=saved.path("id").asText();verifyBytes(service,user,last[index],bytes);written[index]+=length;sequence++;
                    check(disk.getUnallocatedSpace()>=StorageCluster.FLOOR,"actual free blocks remain at least 4 GB after upload");
                }
                if(sequence%32==0)System.out.println("CAPACITY_PROGRESS user1="+written[0]+" user2="+written[1]+" totalFreeBytes="+disk.getUnallocatedSpace());
            }
            for(int user:new int[]{1,2}) {
                final int owner=user;service.trash(user,last[user-1],false);
                expect(413,()->service.upload(request(config,owner),owner,UUID.randomUUID().toString(),List.of(new FileStorage.Upload("over-quota.txt","text/plain",new byte[]{1}))));
                check(count(service,1,"SELECT used_bytes FROM file_storage_users WHERE user_id="+user)==WorkspaceServlet.QUOTA,"full 2 GB quota includes trash");
                service.trash(user,last[user-1],true);
            }
            assertReleased(service);
            System.out.println("PASS: two actual users each stored exactly 2,000,000,000 random file bytes, validated every file body/hash and rejected an extra byte including trash.");
        } finally {
            System.out.println("CAPACITY_RESULT user1="+written[0]+" user2="+written[1]+" initialTotalFreeBytes="+initial+" finalTotalFreeBytes="+disk.getUnallocatedSpace()+" observedAllocatedDelta="+(initial-disk.getUnallocatedSpace()));
            System.out.println("CAPACITY_NOTE: preserve these isolated schemas for measurement; include database/undo/redo/binlog/temp growth and every remote volume separately. No test data or replication logs were deleted.");
        }
    }

    private static XADataSource failMetadataOnce(XADataSource delegate,AtomicBoolean injected) {
        return (XADataSource)Proxy.newProxyInstance(MySqlStorageValidation.class.getClassLoader(),new Class<?>[]{XADataSource.class},(p,m,a)->{
            try {
                Object value=m.invoke(delegate,a);if(!(value instanceof XAConnection connection))return value;
                return Proxy.newProxyInstance(MySqlStorageValidation.class.getClassLoader(),new Class<?>[]{XAConnection.class},(pc,mc,ac)->{
                    try {
                        Object logical=mc.invoke(connection,ac);if(!(logical instanceof Connection c))return logical;
                        return Proxy.newProxyInstance(MySqlStorageValidation.class.getClassLoader(),new Class<?>[]{Connection.class},(pl,ml,al)->{
                            try {
                                Object statement=ml.invoke(c,al);
                                if(!ml.getName().equals("prepareStatement")||al==null||!al[0].toString().startsWith("INSERT INTO library_files")||!(statement instanceof PreparedStatement q))return statement;
                                return Proxy.newProxyInstance(MySqlStorageValidation.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(ps,ms,as)->{
                                    if(ms.getName().equals("executeUpdate")&&injected.compareAndSet(false,true))throw new SQLException("Injected metadata failure after blob INSERT","40001");
                                    try{return ms.invoke(q,as);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                                });
                            }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                        });
                    }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
            }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
    }

    private static void provisionIdentity(Properties config)throws Exception{
        Path store=Path.of(config.getProperty("storage.objectStore"));Files.createDirectories(store);
        try(Connection c=AccountServlet.db(config);Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT store_identity FROM file_storage_coordinator WHERE id=1")){
            r.next();Path marker=store.resolve("identity");if(Files.exists(marker)){check(Files.readString(marker).trim().equals(r.getString(1)),"store identity is immutable");}
            else Files.writeString(marker,r.getString(1),StandardOpenOption.CREATE_NEW);
        }
    }
    private static void awaitReady(FileStorage service)throws Exception{for(int i=0;i<12;i++){service.recover();if(service.ready)return;Thread.sleep(100);}throw new AssertionError("Coordinator did not complete recovery.");}
    private static long count(FileStorage service,int node,String sql)throws Exception{try(Connection c=service.node(node);Statement q=c.createStatement();ResultSet r=q.executeQuery(sql)){r.next();return r.getLong(1);}}
    private static void script(Connection c,Path path)throws Exception{
        String sql=Files.readString(path).replaceAll("(?m)^--.*$","");
        for(String part:sql.split(";"))if(!part.isBlank())try(Statement q=c.createStatement()){
            if(part.trim().equalsIgnoreCase("SET SESSION binlog_format='ROW'")){
                // The isolated group already requires ROW. Runtime test users
                // must not receive privilege to disable binary logging.
                try(ResultSet value=q.executeQuery("SELECT @@session.binlog_format")){
                    if(!value.next()||!"ROW".equalsIgnoreCase(value.getString(1)))throw new SQLException("Validation requires ROW binary logging");
                }
            }else q.execute(part);
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private interface Action{void run()throws Exception;}
    private static void expect(int status,Action action)throws Exception{try{action.run();throw new AssertionError("Expected status "+status);}catch(StorageFailure failure){check(failure.status==status,"Expected "+status+" but received "+failure.status);}}
    private static HttpServletRequest request(Properties config,int node){return (HttpServletRequest)Proxy.newProxyInstance(MySqlStorageValidation.class.getClassLoader(),new Class<?>[]{HttpServletRequest.class},(proxy,method,args)->{
        if(method.getName().equals("getHeader")){if(args[0].equals("X-Starhub-Ingress-Node"))return Integer.toString(node);if(args[0].equals("X-Starhub-Ingress-Token"))return config.getProperty("storage.ingress.token");}return null;});}
    private static XADataSource haltAt(XADataSource delegate,String method,boolean afterCall){
        return (XADataSource)Proxy.newProxyInstance(MySqlStorageValidation.class.getClassLoader(),new Class<?>[]{XADataSource.class},(p,m,a)->{
            if(m.getName().equals("getXAConnection")){
                XAConnection connection=delegate.getXAConnection();
                return Proxy.newProxyInstance(MySqlStorageValidation.class.getClassLoader(),new Class<?>[]{XAConnection.class},(pc,mc,ac)->{
                    if(mc.getName().equals("getXAResource")){XAResource resource=connection.getXAResource();return Proxy.newProxyInstance(MySqlStorageValidation.class.getClassLoader(),new Class<?>[]{XAResource.class},(pr,mr,ar)->{
                        if(mr.getName().equals(method)&&!afterCall)Runtime.getRuntime().halt(42);
                        try{Object result=mr.invoke(resource,ar);if(mr.getName().equals(method)&&afterCall)Runtime.getRuntime().halt(42);return result;}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    });}
                    try{return mc.invoke(connection,ac);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
            }
            try{return m.invoke(delegate,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
    }
    private static XADataSource pauseAfterPrepare(XADataSource delegate,java.util.concurrent.CountDownLatch entered,java.util.concurrent.CountDownLatch release) {
        return (XADataSource)Proxy.newProxyInstance(MySqlStorageValidation.class.getClassLoader(),new Class<?>[]{XADataSource.class},(p,m,a)->{
            try {
                Object value=m.invoke(delegate,a);
                if(!(value instanceof XAConnection connection))return value;
                return Proxy.newProxyInstance(MySqlStorageValidation.class.getClassLoader(),new Class<?>[]{XAConnection.class},(pc,mc,ac)->{
                    try {
                        Object result=mc.invoke(connection,ac);
                        if(!(result instanceof XAResource resource))return result;
                        return Proxy.newProxyInstance(MySqlStorageValidation.class.getClassLoader(),new Class<?>[]{XAResource.class},(pr,mr,ar)->{
                            try {
                                Object answer=mr.invoke(resource,ar);
                                if(mr.getName().equals("prepare")){entered.countDown();if(!release.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new SQLException("test timed out");}
                                return answer;
                            }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                        });
                    }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
            }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
    }
}
