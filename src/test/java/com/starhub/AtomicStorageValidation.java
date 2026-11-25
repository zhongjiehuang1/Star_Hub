package com.starhub;

import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Explicit disposable-schema real-MySQL/real-controller harness. No fake health or capacity probes. */
public final class AtomicStorageValidation {
    private static String schema(String value){
        if(value==null||!value.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/(?:starhub_atomic_test_|starhub_storage_test_)[a-z0-9_]+[?].*"))throw new IllegalArgumentException("Only a private disposable atomic-validation database is allowed");
        URI uri=URI.create(value.substring(5));return uri.getPath().substring(1);
    }
    private static Connection raw(Properties p,String prefix)throws Exception {
        Connection c=DriverManager.getConnection(AccountServlet.jdbcUrl(p,prefix+"url"),p.getProperty(prefix+"user"),p.getProperty(prefix+"password"));
        try(Statement q=c.createStatement()){q.execute("SET SESSION time_zone='+00:00'");}return c;
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void script(Connection c,Path path)throws Exception {
        String sql=Files.readString(path);StringBuilder cleaned=new StringBuilder();for(String line:sql.split("\n"))if(!line.stripLeading().startsWith("--"))cleaned.append(line).append('\n');
        for(String statement:cleaned.toString().split(";"))if(!statement.isBlank())try(Statement q=c.createStatement()){q.execute(statement);}
    }
    private static void create(Properties p)throws Exception {
        String url=p.getProperty("db.url"),base=url.substring(0,url.indexOf('/',"jdbc:mysql://".length())+1)+url.substring(url.indexOf('?'));
        try(Connection c=DriverManager.getConnection(base,p.getProperty("db.user"),p.getProperty("db.password"));Statement q=c.createStatement()){
            for(String key:List.of("db.url","storage.node2.db.url","storage.objectStore.jdbc.url")){
                String schema=schema(p.getProperty(key));
                try(PreparedStatement exists=c.prepareStatement("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=?")){exists.setString(1,schema);try(ResultSet r=exists.executeQuery()){r.next();check(r.getLong(1)==0,"Initialize refuses nonempty validation schemas");}}
                q.execute("CREATE DATABASE IF NOT EXISTS `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
        }
    }
    private static void initialize(Properties p,Path migrations)throws Exception {
        create(p);String password=p.getProperty("validation.accountPassword");if(password==null||password.length()<12)throw new IllegalArgumentException("A synthetic account password must be supplied in the private configuration");
        try(Connection c=raw(p,"db.");Statement q=c.createStatement()){
            q.execute("CREATE TABLE users(id BIGINT NOT NULL PRIMARY KEY,email VARCHAR(254) NOT NULL UNIQUE,password_salt VARBINARY(16) NOT NULL,password_hash VARBINARY(32) NOT NULL,active BOOLEAN NOT NULL DEFAULT TRUE,generation CHAR(36) NOT NULL) ENGINE=InnoDB");
            q.execute("CREATE TABLE provider_keys(user_id BIGINT NOT NULL,provider VARCHAR(40) NOT NULL,nonce VARBINARY(12) NOT NULL,ciphertext BLOB NOT NULL,PRIMARY KEY(user_id,provider),FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT) ENGINE=InnoDB");
            for(long user:List.of(1L,2L)){
                byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);
                try(PreparedStatement insert=c.prepareStatement("INSERT INTO users(id,email,password_salt,password_hash,generation) VALUES(?,?,?,?,?)")){
                    insert.setLong(1,user);insert.setString(2,"atomic-user-"+user+"@example.test");insert.setBytes(3,salt);insert.setBytes(4,AccountServlet.hash(password,salt));insert.setString(5,UUID.randomUUID().toString());insert.executeUpdate();
                }
            }
            for(String name:List.of("001_workspace.sql","002_keyring.sql","003_file_pagination.sql","004_distributed_file_storage.sql","005_file_blob_node.sql","007_atomic_file_transactions.sql","008_durable_login_throttle.sql","009_durable_upload_reservations.sql","010_atomic_guest_sessions.sql","011_provider_operation_receipts.sql","012_chunked_payload_store.sql","013_chunked_business_manifests.sql"))script(c,migrations.resolve(name));
            for(String ddl:AtomicSchemaMigration.plan(c,Set.of(schema(p.getProperty("db.url")),schema(p.getProperty("storage.node2.db.url")))))q.execute(ddl);
        }
        try(Connection c=raw(p,"storage.node2.db.")){script(c,migrations.resolve("005_file_blob_node.sql"));}
        String identity;
        try(Connection c=raw(p,"db.");Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT store_identity FROM file_storage_coordinator WHERE id=1")){check(r.next(),"coordinator marker missing");identity=r.getString(1);}
        try(Connection c=raw(p,"storage.objectStore.jdbc.")){
            script(c,migrations.resolve("starhub-jdbc-decision-store.sql"));
            try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_decision_store_identity(ID,STORE_UUID) VALUES(1,?)")){q.setString(1,identity);q.executeUpdate();}
        }
        marker(p);System.out.println("PASS atomic validation schemas initialized; no production schema touched");
    }
    private static void marker(Properties p)throws Exception {
        Path path=Path.of(p.getProperty("storage.objectStore","")).toAbsolutePath().normalize();
        if(!(path.toString().startsWith("/var/lib/codex-validation/")||path.toString().startsWith("/tmp/starhub-atomic-")))throw new IllegalArgumentException("Only a dedicated validation store is permitted");
        Files.createDirectories(path);String identity;
        try(Connection c=raw(p,"db.");Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT store_identity FROM file_storage_coordinator WHERE id=1")){check(r.next(),"coordinator marker missing");identity=r.getString(1);}
        Path target=path.resolve("identity");if(Files.exists(target)){check(Files.readString(target).trim().equals(identity),"validation store identity changed");return;}
        Files.writeString(target,identity+"\n",StandardOpenOption.CREATE_NEW);
    }
    private static HttpServletRequest request(Properties p,int ingress){
        return (HttpServletRequest)Proxy.newProxyInstance(AtomicStorageValidation.class.getClassLoader(),new Class<?>[]{HttpServletRequest.class},(proxy,method,args)->{
            if(method.getName().equals("getHeader"))return switch((String)args[0]){case "X-Starhub-Ingress-Node"->Integer.toString(ingress);case "X-Starhub-Ingress-Token"->p.getProperty("storage.ingress.token");default->null;};
            return null;
        });
    }
    private static List<FileStorage.Upload> data(long user,int bytes,long seed,boolean batch){
        if(bytes<1||bytes>AtomicFileStorage.MAX_OPERATION_BYTES)throw new IllegalArgumentException("Invalid bounded synthetic payload");
        Random random=new Random(seed);List<FileStorage.Upload> files=new ArrayList<>();int remaining=bytes,index=0;
        while(remaining>0){int size=batch?Math.min(remaining,GeneratedImages.MAX_IMAGE):remaining;if(size>GeneratedImages.MAX_IMAGE)throw new IllegalArgumentException("Use batch for generated images larger than one file");byte[] value=new byte[size];random.nextBytes(value);files.add(new FileStorage.Upload("atomic-"+user+"-"+(index++)+".txt","text/plain",value));remaining-=size;}
        return files;
    }
    private static void reserve(FileStorage service,Properties p,long user,String op,List<FileStorage.Upload> files)throws Exception {
        var metadata=ModelGateway.JSON.createArrayNode();for(var file:files){var item=file.metadata();item.remove("id");metadata.add(item);}
        var reserved=service.atomic.reserve(user,op,metadata,Integer.parseInt(p.getProperty("server.id")));
        check(Set.of("RESERVED","COMMITTED").contains(reserved.path("state").asText()),"Reservation did not reach a definite state");
    }
    private static void verify(FileStorage service,long user,String file,byte[] expected)throws Exception {
        byte[] actual=Base64.getDecoder().decode(service.read(user,file).path("data").asText());check(MessageDigest.isEqual(FileStorage.hash(expected),FileStorage.hash(actual))&&Arrays.equals(expected,actual),"Full random bytes/SHA-256 mismatch");
    }
    private static void verifyUploads(FileStorage service,long user,com.fasterxml.jackson.databind.node.ObjectNode result,List<FileStorage.Upload> files)throws Exception {
        check("COMMITTED".equals(result.path("state").asText())&&result.path("ids").size()==files.size(),"Published batch metadata does not match its immutable plan");
        for(int index=0;index<files.size();index++)verify(service,user,result.path("ids").get(index).asText(),files.get(index).content);
        result.put("fullContentMatch",true).put("verifiedFiles",files.size()).put("verifiedBytes",files.stream().mapToLong(file->file.content.length).sum());
    }
    private static void basic(FileStorage service,Properties p)throws Exception {
        for(int ingress:List.of(1,2)){
            long user=ingress;String operation=UUID.randomUUID().toString();List<FileStorage.Upload> payload=data(user,4*1024*1024,72+user,false);
            var result=service.upload(request(p,ingress),user,operation,payload);String file=result.path("id").asText();verify(service,user,file,payload.get(0).content);
            service.atomic.database.run(UUID.randomUUID().toString(),c->{
                try(PreparedStatement q=c.prepareStatement("SELECT ABS(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP())) FROM library_files WHERE id=?")){
                    q.setString(1,file);try(ResultSet time=q.executeQuery()){check(time.next()&&time.getLong(1)<=30,"New file created_at differs from current UTC");}
                }return null;
            });
            check(service.upload(request(p,3-ingress),user,operation,data(user,4*1024*1024,72+user,false)).path("id").asText().equals(file),"Cross-ingress replay changed file identity");
            try{service.upload(request(p,ingress),user,operation,data(user,4*1024*1024,99+user,false));throw new AssertionError("Changed content reused operation");}catch(StorageFailure conflict){check(conflict.status==409,"Changed content did not return conflict");}
            try{service.read(3-user,file);throw new AssertionError("Cross-account read permitted");}catch(StorageFailure denied){check(denied.status==404,"Cross-account denial did not preserve isolation");}
            String trash=UUID.randomUUID().toString();service.trash(user,trash,file,false);service.trash(user,UUID.randomUUID().toString(),file,true);service.trash(user,trash,file,false);verify(service,user,file,payload.get(0).content);
            service.trash(user,UUID.randomUUID().toString(),file,false);String deletion=UUID.randomUUID().toString();service.delete(user,deletion,file);service.delete(user,deletion,file);
        }
        check(service.transactions==null,"Atomic path started an XA recovery engine");
        System.out.println("PASS real atomic files: two users/ingress, full random bytes, replay/conflict, isolation, delayed trash replay, restore and deletion; no XA engine");
    }
    record Quota(long used,long reserved){long remaining(){return Math.max(0,WorkspaceServlet.QUOTA-used-reserved);}}
    static Quota quota(AtomicFileStorage storage,long user)throws Exception {
        return storage.database.run(UUID.randomUUID().toString(),c->{
            FileStorage.lockUser(c,user);
            try(PreparedStatement q=c.prepareStatement("SELECT used_bytes,reserved_bytes,(SELECT COALESCE(SUM(size_bytes),0) FROM library_files WHERE user_id=?) FROM file_storage_users WHERE user_id=?")){
                q.setLong(1,user);q.setLong(2,user);try(ResultSet r=q.executeQuery()){check(r.next(),"Actual quota row missing");long used=r.getLong(1),reserved=r.getLong(2);check(used==r.getLong(3)&&used>=0&&reserved>=0,"Actual catalogue and quota disagree");return new Quota(used,reserved);}
            }
        });
    }
    private record Prior(String state,long bytes){}
    private static Prior prior(AtomicFileStorage storage,long user,String operation)throws Exception {
        return storage.database.run(operation,c->{
            String generation=FileStorage.lockUser(c,user);try(PreparedStatement q=c.prepareStatement("SELECT state,logical_bytes,user_id,user_generation,kind FROM file_storage_operations WHERE op_id=?")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){if(!r.next())return null;check(r.getLong(3)==user&&generation.equals(r.getString(4))&&"UPLOAD".equals(r.getString(5)),"Existing capacity operation belongs to another request");return new Prior(r.getString(1),r.getLong(2));}}
        });
    }
    private static void capacityReport(String state,Quota[] quota,long before,Path volume)throws Exception {
        System.out.println(ModelGateway.JSON.createObjectNode().put("state",state).put("user1Bytes",quota[0].used).put("user2Bytes",quota[1].used).put("user1ReservedBytes",quota[0].reserved).put("user2ReservedBytes",quota[1].reserved).put("physicalAllocatedGrowth",before-Files.getFileStore(volume).getUnallocatedSpace()));
    }
    private static void release(Path path,String label,String operation)throws Exception {
        if(!path.toAbsolutePath().normalize().startsWith(Path.of("/var/lib/codex-validation/"))||Files.exists(path))throw new IllegalArgumentException("A new private disposable barrier-release path is required");
        System.out.println(label+" "+operation);System.out.flush();long deadline=System.nanoTime()+120_000_000_000L;
        while(!Files.exists(path)){if(System.nanoTime()>=deadline)throw new SQLException("Disposable validation barrier expired","08006");Thread.sleep(100);}
    }
    private static AtomicDatabase afterLocalCommit(Properties p,String operation,Path release)throws Exception {return afterLocalCommit(p,operation,release,false);}
    private static AtomicDatabase afterLocalCommit(Properties p,String operation,Path release,boolean terminalProviderOnly)throws Exception {
        var paused=new java.util.concurrent.atomic.AtomicBoolean();AtomicDatabase.Connections actual=()->{
            Connection physical=AccountServlet.unscopedDb(p);
            return (Connection)Proxy.newProxyInstance(AtomicStorageValidation.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try{boolean eligible=true;if(terminalProviderOnly&&method.getName().equals("commit")){eligible=false;try(PreparedStatement q=physical.prepareStatement("SELECT state FROM starhub_provider_operations WHERE operation_id=?")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){eligible=r.next()&&java.util.Set.of("COMPLETED","COMPLETED_NO_RESULT").contains(r.getString(1));}}}Object result=method.invoke(physical,args);if(method.getName().equals("commit")&&eligible&&paused.compareAndSet(false,true))release(release,"READY_AFTER_LOCAL_COMMIT",operation);return result;}
                catch(java.lang.reflect.InvocationTargetException failed){throw failed.getCause();}
            });
        };
        return new AtomicDatabase(p,actual,new AtomicDatabase.Authority(){
            public ReplicaAuthority.Lease acquire()throws SQLException{return ReplicaAuthority.lease(p);}
            public void unchanged(ReplicaAuthority.Lease lease)throws SQLException{ReplicaAuthority.unchanged(p,lease);}
            public void confirm(ReplicaAuthority.Lease lease,String gtid,String op)throws SQLException{ReplicaAuthority.confirm(p,lease,gtid,op);}
        });
    }
    private static AtomicDatabase afterImageCommit(Properties p,String operation,Path file,boolean parentOnly)throws Exception {
        var paused=new java.util.concurrent.atomic.AtomicBoolean();AtomicDatabase.Connections connections=()->{
            Connection physical=AccountServlet.unscopedDb(p);
            return (Connection)Proxy.newProxyInstance(AtomicStorageValidation.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try{boolean eligible=false;if(method.getName().equals("commit")){
                    String sql=parentOnly?"SELECT COUNT(*) FROM starhub_provider_payloads WHERE operation_id=?":"SELECT COUNT(*) FROM starhub_payload_chunks c JOIN starhub_payload_manifests m ON m.payload_id=c.payload_id WHERE m.operation_id=? AND m.kind='PROVIDER_IMAGE'";
                    try(PreparedStatement q=physical.prepareStatement(sql)){q.setString(1,operation);try(ResultSet r=q.executeQuery()){eligible=r.next()&&r.getLong(1)>0;}}
                }Object result=method.invoke(physical,args);if(method.getName().equals("commit")&&eligible&&paused.compareAndSet(false,true))release(file,parentOnly?"READY_AFTER_LOCAL_GALLERY_PARENT":"READY_AFTER_LOCAL_IMAGE_CHUNK",operation);return result;}
                catch(java.lang.reflect.InvocationTargetException failed){throw failed.getCause();}
            });
        };
        return new AtomicDatabase(p,connections,new AtomicDatabase.Authority(){
            public ReplicaAuthority.Lease acquire()throws SQLException{return ReplicaAuthority.lease(p);}public void unchanged(ReplicaAuthority.Lease lease)throws SQLException{ReplicaAuthority.unchanged(p,lease);}public void confirm(ReplicaAuthority.Lease lease,String gtid,String op)throws SQLException{ReplicaAuthority.confirm(p,lease,gtid,op);}
        });
    }
    private static void gallery(FileStorage service,Properties p,String[] args)throws Exception {
        String phase=args[2],operation=FileStorage.operationId(args[3]);long seed=Long.parseLong(args[4]);int size=Integer.parseInt(args[5]),count=Integer.parseInt(args[6]);
        check(size>0&&size<=GeneratedImages.MAX_IMAGE&&count>=1&&count<=4,"Bounded gallery size/count required");boolean account=phase.startsWith("gallery-account");
        if(!Set.of("gallery-guest","gallery-guest-read","gallery-guest-status","gallery-guest-after-parent","gallery-guest-after-chunk","gallery-guest-after-result","gallery-account","gallery-account-read","gallery-account-status","gallery-account-after-file").contains(phase))throw new IllegalArgumentException("Unknown disposable gallery phase");
        String tab=operation.replace("-","");ProviderOperations.Owner principal;
        if(account){String generation=service.atomic.database.run(operation,c->FileStorage.lockUser(c,1));principal=new ProviderOperations.Owner("account:1:"+generation+":"+tab,c->{if(!generation.equals(FileStorage.lockUser(c,1)))throw StorageFailure.unavailable();});}
        else{
            String cookie="disposable-gallery-validation:"+operation;var request=ModelGateway.JSON.createObjectNode().put("action","identity").put("tab",tab).put("tx",tab);
            var identity=service.atomic.guest.handle(cookie,null,request,System.currentTimeMillis());String guest=identity.identity();principal=new ProviderOperations.Owner("guest:"+guest+":"+tab,c->{try(PreparedStatement q=c.prepareStatement("SELECT expires_at_millis FROM starhub_guest_sessions WHERE guest_id=?")){q.setString(1,guest);try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong(1)<=System.currentTimeMillis())throw StorageFailure.unavailable();}}});
        }
        var operations=service.atomic.providers;var request=ModelGateway.JSON.createObjectNode().put("operationId",operation).put("provider","synthetic-validation-only-no-network").put("payloadSeed",seed).put("size",size);request.putObject("parameters").put("n",count);
        ProviderOperations.Receipt receipt;
        if(phase.endsWith("-status")||phase.endsWith("-read"))receipt=operations.status(principal,operation);
        else{
            receipt=operations.claim(principal,operation,"validation:image",request);
            if(receipt.state().equals("CLAIMED")){
                var ticket=operations.dispatch(principal,operation,"validation:image",request);
                try(var context=ticket.enter()){ProviderOperations.beforeExternal();System.out.println("SYNTHETIC_GALLERY_EFFECT_ONLY "+operation);System.out.flush();}
                if(phase.endsWith("-after-parent")||phase.endsWith("-after-chunk")||phase.endsWith("-after-result")){
                    Path file=Path.of(args[7]);AtomicDatabase actual=phase.endsWith("-after-result")?afterLocalCommit(p,operation,file,true):afterImageCommit(p,operation,file,phase.endsWith("-after-parent"));
                    ProviderOperations completion=new ProviderOperations(actual,service.cluster,p);ticket=completion.new Ticket(principal,operation,ticket.dispatchHash,ticket.lease);
                }
                var result=ModelGateway.JSON.createObjectNode().put("text","synthetic-four-image-gallery");var media=result.putArray("media");List<FileStorage.Upload> files=new ArrayList<>();Random random=new Random(seed);
                for(int index=0;index<count;index++){byte[] bytes=new byte[size];random.nextBytes(bytes);media.addObject().put("type","image").put("mime","image/png").put("imageBytes",bytes);files.add(new FileStorage.Upload("synthetic-gallery-"+index+".png","image/png",bytes));}
                if(account)try(var context=ticket.enter()){
                    var metadata=ModelGateway.JSON.createArrayNode();for(var upload:files){var item=upload.metadata();item.remove("id");metadata.add(item);}var reserved=service.atomic.reserve(1,operation,metadata,Integer.parseInt(p.getProperty("server.id")));
                    for(int index=0;index<count;index++)((com.fasterxml.jackson.databind.node.ObjectNode)media.get(index)).put("fileId",reserved.path("ids").get(index).asText());ticket.prepareAccountImages(result);
                    service.atomic.complete(request(p,Integer.parseInt(p.getProperty("server.id"))),1,operation,files);
                    if(phase.equals("gallery-account-after-file"))release(Path.of(args[7]),"READY_AFTER_CONFIRMED_LIBRARY_COMMIT",operation);
                }
                receipt=ticket.complete(200,result);
            }
        }
        var out=ModelGateway.JSON.createObjectNode().put("operationId",operation).put("operationState",receipt.state()).put("durability",receipt.durability()).put("syntheticEffectOnly",true);
        if(phase.endsWith("-read")){
            check(receipt.state().equals("COMPLETED")&&receipt.result().path("media").size()==count,"Gallery not completely published");Random random=new Random(seed);var checked=out.putArray("images");
            for(int index=0;index<count;index++){byte[] expected=new byte[size];random.nextBytes(expected);var item=receipt.result().path("media").get(index);String id=account?item.path("fileId").asText():item.path("mediaRef").path("mediaId").asText();var image=operations.media(principal,operation,id);byte[] actual=image.fileId()==null?image.bytes():service.atomic.readBinary(1,image.fileId()).bytes();check(Arrays.equals(expected,actual),"Gallery bytes/SHA do not match original synthetic payload");checked.addObject().put("mediaId",id).put("bytes",actual.length).put("sha256",HexFormat.of().formatHex(FileStorage.hash(actual))).put("durability",image.durability());}
            out.put("fullContentMatch",true);
        }else if(receipt.result()!=null)out.put("smallReceiptBytes",ModelGateway.JSON.writeValueAsBytes(receipt.result()).length);
        System.out.println(out);
    }
    public static void main(String[] args){
        try{run(args);}catch(Throwable failed){System.err.println("VALIDATION_FAILED type="+failed.getClass().getSimpleName()+(failed instanceof SQLException sql?" sqlState="+sql.getSQLState():failed instanceof StorageFailure storage?" status="+storage.status+" code="+storage.code:""));System.exit(2);}
    }
    private static void run(String[] args)throws Exception {
        if(args.length<3)throw new IllegalArgumentException("private-config migration-directory initialize|marker|basic|upload|batch|hold|after-commit|status|capacity");
        Properties p=new Properties();try(InputStream input=Files.newInputStream(Path.of(args[0]))){p.load(input);}
        for(String key:List.of("db.url","storage.node2.db.url","storage.objectStore.jdbc.url"))schema(p.getProperty(key));
        Class.forName("com.mysql.cj.jdbc.Driver");try(Connection c=raw(p,"db.")){
            try(Statement clock=c.createStatement();ResultSet time=clock.executeQuery("SELECT ABS(TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW()))")){check(time.next()&&time.getLong(1)==0,"Validation JDBC session must use UTC");}
            check(c.getMetaData().getDatabaseProductVersion().startsWith("26.10."),"Current MySQL 26.10 is required");
            System.out.println("REAL_MYSQL "+c.getMetaData().getDatabaseProductVersion()+" JDBC "+c.getMetaData().getDriverVersion());
            try(PreparedStatement prepared=c.prepareStatement("SELECT ?")){check(prepared.getClass().getName().equals("com.mysql.cj.jdbc.ServerPreparedStatement"),"Validation must use actual server-prepared JDBC statements");prepared.setInt(1,1);try(ResultSet actual=prepared.executeQuery()){check(actual.next()&&actual.getInt(1)==1,"Server-prepared statement failed");}System.out.println("JDBC_SERVER_PREPARED true; JDBC_SESSION_UTC true");}
        }catch(SQLSyntaxErrorException missing){if(!args[2].equals("initialize"))throw missing;}
        String phase=args[2];if(phase.equals("initialize")){initialize(p,Path.of(args[1]));return;}if(phase.equals("marker")){marker(p);System.out.println("PASS local validation marker provisioned");return;}
        System.setProperty("starhub.account.config",Path.of(args[0]).toAbsolutePath().toString());FileStorage service=new FileStorage(p);FileStorage.active=service;
        try{
            for(int i=0;i<30&&!service.ready;i++){service.recover();Thread.sleep(100);}service.ensureReady();
            if(phase.equals("basic")){basic(service,p);return;}
            if(phase.startsWith("gallery-")){gallery(service,p,args);return;}
            if(phase.startsWith("provider-")){
                if(!java.util.Set.of("provider-claim","provider-run","provider-status","provider-hold-sent","provider-after-local-sent","provider-after-effect","provider-after-local-result","provider-max").contains(phase))throw new IllegalArgumentException("Unknown synthetic provider phase");
                String operation=FileStorage.operationId(args[3]);long seed=Long.parseLong(args[4]);ProviderOperations operations=service.atomic.providers;
                var owner=new ProviderOperations.Owner("disposable-provider-validation:user:1",c->FileStorage.lockUser(c,1));
                var input=ModelGateway.JSON.createObjectNode().put("operationId",operation).put("provider","synthetic-validation-only-no-network").put("payloadSeed",seed);
                ProviderOperations.Receipt result;
                if(phase.equals("provider-status"))result=operations.status(owner,operation);
                else {
                    result=operations.claim(owner,operation,"validation:synthetic-effect",input);
                    if(!phase.equals("provider-claim")&&result.state().equals("CLAIMED")){
                        ProviderOperations dispatcher=phase.equals("provider-after-local-sent")?new ProviderOperations(afterLocalCommit(p,operation,Path.of(args[5])),service.cluster,p):operations;
                        ProviderOperations.Ticket ticket=dispatcher.dispatch(owner,operation,"validation:synthetic-effect",input);
                        if(phase.equals("provider-hold-sent"))release(Path.of(args[5]),"READY_AFTER_DURABLE_SENT",operation);
                        try(AutoCloseable context=ticket.enter()){
                            ProviderOperations.beforeExternal();
                            // This is deliberately a synthetic side-effect marker,
                            // never an HTTP/WebSocket request to a paid provider.
                            System.out.println("SYNTHETIC_EFFECT_ONLY "+operation);System.out.flush();
                            if(phase.equals("provider-after-effect"))release(Path.of(args[5]),"READY_AFTER_SYNTHETIC_EFFECT",operation);
                        }
                        if(phase.equals("provider-after-local-result")){
                            ProviderOperations completion=new ProviderOperations(afterLocalCommit(p,operation,Path.of(args[5]),true),service.cluster,p);
                            ticket=completion.new Ticket(owner,operation,ticket.dispatchHash,ticket.lease);
                        }
                        var response=ModelGateway.JSON.createObjectNode().put("text","synthetic-provider-result:"+seed);
                        if(phase.equals("provider-max")){var skeleton=ModelGateway.JSON.createObjectNode().put("text","").put("operationId",operation).put("operationState","COMPLETED");int length=ProviderOperations.RESULT_LIMIT-ModelGateway.JSON.writeValueAsBytes(skeleton).length;Random random=new Random(seed);StringBuilder text=new StringBuilder(length);for(int i=0;i<length;i++)text.append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".charAt(random.nextInt(64)));response.put("text",text.toString());}
                        result=ticket.complete(200,response);
                    }
                }
                var out=ModelGateway.JSON.createObjectNode().put("operationId",operation).put("operationState",result.state()).put("durability",result.durability()).put("syntheticEffectOnly",true);if(result.result()!=null){String text=result.result().path("text").asText();out.put("resultTextBytes",text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).put("resultTextSha256",HexFormat.of().formatHex(FileStorage.hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))));}System.out.println(out);return;
            }
            if(java.util.Set.of("guest-write","guest-read","guest-status","guest-hold","guest-after-commit","guest-cleanup").contains(phase)){
                String run=FileStorage.operationId(args[3]),tab=run.replace("-","");long version=Long.parseLong(args[4]);int units=Integer.parseInt(args[5]);long seed=Long.parseLong(args[6]);if(units<1||units>GuestSessionServlet.MAX_FIELD-12)throw new IllegalArgumentException("Bounded synthetic guest payload required");
                Random random=new Random(seed);StringBuilder value=new StringBuilder(units);for(int i=0;i<units;i++)value.append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".charAt(random.nextInt(64)));String cookie="disposable-guest-validation:"+run,tx=UUID.nameUUIDFromBytes((run+":"+version+":"+seed).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-","");
                var base=ModelGateway.JSON.createObjectNode().put("tab",tab).put("field","history").put("tx",tx);var commit=base.deepCopy().put("action","commit");AtomicGuestStorage.Reply result;
                if(phase.equals("guest-read")){result=service.atomic.guest.handle(cookie,null,ModelGateway.JSON.createObjectNode().put("tab",tab).put("action","get"),System.currentTimeMillis());String actual=result.body().path("history").path("value").asText();check(value.toString().equals(actual),"Full synthetic guest payload mismatch");System.out.println(ModelGateway.JSON.createObjectNode().put("state","GUEST_FULL_CONTENT_MATCH").put("units",units).put("sha256",HexFormat.of().formatHex(FileStorage.hash(actual.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).put("durability",result.durability()));return;}
                if(phase.equals("guest-status"))result=service.atomic.guest.handle(cookie,null,ModelGateway.JSON.createObjectNode().put("tab",tab).put("action","status").put("operationId",AtomicGuestStorage.operationId(commit)),System.currentTimeMillis());
                else if(phase.equals("guest-cleanup"))result=service.atomic.guest.handle(cookie,null,base.deepCopy().put("action","renew"),System.currentTimeMillis());
                else{
                    result=service.atomic.guest.handle(cookie,null,base.deepCopy().put("action","begin").put("version",version),System.currentTimeMillis());String encoded=ModelGateway.JSON.createObjectNode().put("value",value.toString()).toString();check(encoded.length()<=GuestSessionServlet.MAX_FIELD,"Synthetic JSON must fit the exact guest-field code-unit limit");
                    for(int start=0;start<encoded.length();start+=100000)service.atomic.guest.handle(cookie,result.identity(),base.deepCopy().put("action","chunk").put("index",start/100000).put("data",encoded.substring(start,Math.min(encoded.length(),start+100000))),System.currentTimeMillis());
                    String operation=AtomicGuestStorage.operationId(commit);
                    if(phase.equals("guest-hold"))try(AtomicDatabase.Scope scope=service.atomic.database.begin(operation)){result=service.atomic.guest.handle(cookie,result.identity(),commit,System.currentTimeMillis());release(Path.of(args[7]),"READY_BEFORE_COMMIT",operation);scope.commit();}
                    else if(phase.equals("guest-after-commit")){AtomicDatabase database=afterLocalCommit(p,operation,Path.of(args[7]));result=new AtomicGuestStorage(database,p,service.atomic::transientCapacity).handle(cookie,result.identity(),commit,System.currentTimeMillis());}
                    else result=service.atomic.guest.handle(cookie,result.identity(),commit,System.currentTimeMillis());
                }
                System.out.println(result.body().put("durability",result.durability()));return;
            }

            if(phase.equals("status")){System.out.println(service.status(Long.parseLong(args[4]),args[3]));return;}
            if(phase.equals("upload")||phase.equals("batch")||phase.equals("hold")||phase.equals("after-commit")){
                String op=FileStorage.operationId(args[3]);long user=Long.parseLong(args[4]);int bytes=Integer.parseInt(args[5]);long seed=Long.parseLong(args[6]);
                List<FileStorage.Upload> data=data(user,bytes,seed,phase.equals("batch")||bytes>GeneratedImages.MAX_IMAGE);
                if(phase.equals("hold")||phase.equals("after-commit"))reserve(service,p,user,op,data);
                if(phase.equals("hold")||phase.equals("after-commit"))service.atomic.stageContent(request(p,Integer.parseInt(p.getProperty("server.id"))),user,op,data);
                if(phase.equals("after-commit")){
                    AtomicDatabase actual=afterLocalCommit(p,op,Path.of(args[7]));AtomicFileStorage worker=new AtomicFileStorage(actual,service.cluster);
                    try(AtomicDatabase.Scope scope=actual.begin(op)){var result=worker.complete(request(p,Integer.parseInt(p.getProperty("server.id"))),user,op,data);scope.commit();System.out.println(result.put("durability",scope.lease.mode()));}return;
                }
                if(phase.equals("hold"))try(AtomicDatabase.Scope scope=service.atomic.database.begin(op)){
                    var result=service.atomic.complete(request(p,Integer.parseInt(p.getProperty("server.id"))),user,op,data);
                    System.out.println("READY_BEFORE_COMMIT "+op);System.out.flush();long delay=Long.parseLong(args[7]);if(delay<1||delay>120000)throw new IllegalArgumentException("Bounded pause required");Thread.sleep(delay);scope.commit();System.out.println(result);
                }else {
                    var result=service.upload(request(p,Integer.parseInt(p.getProperty("server.id"))),user,op,data);var proof=AtomicDatabase.confirmation();check(proof!=null&&op.equals(proof.operation()),"Upload lacks matching actual controller confirmation");result.put("durability",proof.lease().mode());verifyUploads(service,user,result,data);System.out.println(result);
                }
                return;
            }
            if(phase.equals("capacity")){
                String run=p.getProperty("validation.capacity.runId","");UUID.fromString(run);
                Path volume=Path.of(p.getProperty("validation.capacity.volumePath","")).toAbsolutePath();if(!volume.toString().startsWith("/var/lib/codex-validation/"))throw new IllegalArgumentException("Isolated data volume path required");
                long before=Files.getFileStore(volume).getUnallocatedSpace();Quota[] actual={quota(service.atomic,1),quota(service.atomic,2)};int sequence=0,completed=0;
                while(actual[0].remaining()>0||actual[1].remaining()>0){
                    int current=sequence++,index=current%2;if(actual[index].remaining()==0)continue;
                    check(Files.getFileStore(volume).getUnallocatedSpace()>=StorageCluster.FLOOR,"Actual filesystem crossed reserve before operation");
                    String op=UUID.nameUUIDFromBytes((run+":"+current).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();Prior previous=prior(service.atomic,index+1,op);
                    // Existing committed operations are already included in SQL
                    // used_bytes; never count a replay as newly stored content.
                    if(previous!=null&&!"RESERVED".equals(previous.state))continue;
                    int length=(int)(previous==null?Math.min(WorkspaceServlet.FILE_MAX,actual[index].remaining()):previous.bytes);
                    check(length>0&&length<=WorkspaceServlet.FILE_MAX,"Existing synthetic capacity reservation has an unexpected size");List<FileStorage.Upload> payload=data(index+1,length,current,false);
                    try{var saved=service.upload(request(p,index+1),index+1,op,payload);verify(service,index+1,saved.path("id").asText(),payload.get(0).content);}
                    catch(StorageFailure refusal){if(refusal.status!=507)throw refusal;actual[0]=quota(service.atomic,1);actual[1]=quota(service.atomic,2);capacityReport("REFUSED_507",actual,before,volume);return;}
                    actual[index]=quota(service.atomic,index+1);completed++;check(Files.getFileStore(volume).getUnallocatedSpace()>=StorageCluster.FLOOR,"Actual filesystem crossed reserve after operation");
                    if(completed%32==0){System.out.println("CAPACITY_PROGRESS newFiles="+completed+" user1="+actual[0].used+" user2="+actual[1].used+" reserved1="+actual[0].reserved+" reserved2="+actual[1].reserved);System.out.flush();}
                }
                actual[0]=quota(service.atomic,1);actual[1]=quota(service.atomic,2);
                capacityReport(actual[0].used==WorkspaceServlet.QUOTA&&actual[1].used==WorkspaceServlet.QUOTA?"TWO_REAL_USERS_AT_QUOTA":"PENDING_RESERVATIONS_PREVENT_FULL_STORED_QUOTA",actual,before,volume);return;
            }
            throw new IllegalArgumentException("Unknown atomic validation phase");
        }finally{service.close();FileStorage.active=null;}
    }
}
