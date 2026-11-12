package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.mysql.cj.jdbc.MysqlXADataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import jakarta.servlet.http.HttpServletRequest;
import javax.sql.XADataSource;
import javax.transaction.xa.Xid;

/** Equal physical writers use atomic local SQL; isolated legacy tests retain XA. */
class FileStorage implements XaTransactions.Journal,AutoCloseable {
    static volatile FileStorage active;
    final Properties config;final StorageCluster cluster;final boolean replicated,multiPhysical;
    final Map<Integer,XADataSource> sources=new TreeMap<>();
    final Set<String> executing=ConcurrentHashMap.newKeySet(),uncertain=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.Semaphore replicaAdmission=new java.util.concurrent.Semaphore(1);
    final XaTransactions transactions;
    final AtomicFileStorage atomic;
    private java.nio.channels.FileChannel atomicLockChannel;
    private java.nio.channels.FileLock atomicProcessLock;
    volatile boolean ready,closing;
    private final ReentrantReadWriteLock lifecycle=new ReentrantReadWriteLock(true);
    private final ScheduledExecutorService recovery=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"starhub-file-recovery");t.setDaemon(true);return t;});

    static final class Upload {
        final String name,mime,id;final byte[] content,digest;
        Upload(String name,String mime,byte[] content){if(name==null||name.isBlank()||name.length()>255||content==null||content.length==0||content.length>GeneratedImages.MAX_IMAGE||!WorkspaceServlet.TYPES.contains(mime))throw new StorageFailure(400,"INVALID_FILE","文件类型或大小不受支持。");this.name=name;this.mime=mime;this.content=content;id=UUID.randomUUID().toString();digest=hash(content);}
        ObjectNode metadata(){return ModelGateway.JSON.createObjectNode().put("id",id).put("name",name).put("mime",mime).put("size",content.length).put("sha256",Base64.getEncoder().encodeToString(digest));}
    }
    static final class Operation {
        String id,generation,kind,state,files,uid;long user,logical,physical,primaryPhysical;int node;
        Set<Integer> participants(){return node==1?Set.of(1):Set.of(1,2);}
        ObjectNode result()throws Exception{
            ObjectNode result=ModelGateway.JSON.createObjectNode().put("operationId",id).put("state",state);
            ArrayNode ids=result.putArray("ids");for(JsonNode file:ModelGateway.JSON.readTree(files))ids.add(file.path("id").asText());
            if(ids.size()==1)result.put("id",ids.get(0).asText());result.put("ok","COMMITTED".equals(state));return result;
        }
    }

    FileStorage(Properties config)throws Exception{
        this.config=config;cluster=new StorageCluster(config);
        String replicaMode=config.getProperty("storage.replicated","false");
        if(!"true".equals(replicaMode)&&!"false".equals(replicaMode))throw new IllegalArgumentException("Invalid replicated storage mode");
        replicated="true".equals(replicaMode);
        multiPhysical=!config.getProperty("storage.physicalNodeIds","").isBlank();
        if(multiPhysical){if(!replicated)throw new IllegalArgumentException("Physical replica membership requires replicated mode");cluster.physicalNodeIds();}
        if(!replicated)requireIsolatedLegacy(config);
        if(replicated&&!"jdbc".equals(config.getProperty("storage.objectStore.backend")))throw new IllegalArgumentException("Replicated storage requires the database decision store");
        if(replicated)ReplicaAuthority.validateEndpoints(config);
        if(!"true".equals(config.getProperty("storage.enabled"))||!"true".equals(config.getProperty("storage.coordinator")))throw StorageFailure.unavailable();
        if(replicated&&!"atomic".equals(config.getProperty("storage.transactionMode")))
            throw new IllegalArgumentException("Replicated storage requires the atomic transaction migration; legacy XA is not a multi-writer mode");
        if(replicated&&!cluster.physicalNodeIds().equals(Set.of(1,2)))
            throw new IllegalArgumentException("Atomic storage requires the explicit two physical members");
        for(int node:new int[]{1,2}){
            String prefix=node==1?"db.":"storage.node2.db.";
            MysqlXADataSource source=new MysqlXADataSource();source.setUrl(AccountServlet.jdbcUrl(config,prefix+"url"));
            source.setUser(cluster.required(prefix+"user"));source.setPassword(cluster.required(prefix+"password"));
            source.setConnectTimeout(3000);source.setSocketTimeout(AccountServlet.databaseSocketTimeout(config));source.setPinGlobalTxToPhysicalConnection(true);sources.put(node,source);
        }
        Path store=Path.of(cluster.required("storage.objectStore"));
        // Provision this marker with the migration while all writers are stopped.
        // An unexpected empty/replaced store must never become presumed-abort proof.
        String identity=Files.readString(store.resolve("identity")).trim();
        try(Connection c=primary();PreparedStatement q=c.prepareStatement("SELECT store_identity FROM file_storage_coordinator WHERE id=1");ResultSet r=q.executeQuery()){
            if(!r.next()||!identity.equals(r.getString(1)))throw StorageFailure.unavailable();
        }
        if(replicated){
            JdbcDecisionStore.requireEmptyLegacyStore(store);
            atomicLockChannel=java.nio.channels.FileChannel.open(store.resolve("coordinator.lock"),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE);
            atomicProcessLock=atomicLockChannel.tryLock();
            if(atomicProcessLock==null){atomicLockChannel.close();throw new IllegalStateException("Another local storage writer is active");}
            try{
                var oldStore=JdbcDecisionStore.from(config,identity);
                try(Connection c=oldStore.getConnection();Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM starhub_JBossTSTxTable")){
                    if(!r.next()||r.getLong(1)!=0)throw new SQLException("Legacy XA decisions must be recovered before atomic startup");
                }
                atomic=new AtomicFileStorage(this);transactions=null;ready=true;
            }catch(Exception failure){atomicProcessLock.release();atomicLockChannel.close();throw failure;}
        }else{
            atomic=null;transactions=new XaTransactions(sources,this,store,cluster.required("storage.transactionNode"),JdbcDecisionStore.from(config,identity),60,
                    connection->ReplicaAuthority.verify(connection,config));
        }
        recovery.scheduleWithFixedDelay(this::recover,0,5,TimeUnit.SECONDS);
    }
    static void requireIsolatedLegacy(Properties config){
        // Legacy presumed-abort/XA must never be a fallback for a business schema.
        for(String key:List.of("db.url","storage.node2.db.url")){
            String url=config.getProperty(key,"");
            if(!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/(?:starhub_atomic_test_|starhub_storage_test_)[a-z0-9_]+[?].*"))
                throw new IllegalArgumentException("Legacy XA is restricted to loopback disposable validation schemas; business storage requires atomic replicated mode");
        }
        if("jdbc".equals(config.getProperty("storage.objectStore.backend"))){
            String url=config.getProperty("storage.objectStore.jdbc.url","");
            if(!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/(?:starhub_atomic_test_|starhub_storage_test_)[a-z0-9_]+[?].*"))
                throw new IllegalArgumentException("Legacy decisions require a disposable loopback validation schema");
        }
    }
    static FileStorage get(){FileStorage service=active;if(service==null||service.closing)throw StorageFailure.unavailable();return service;}
    Connection primary()throws SQLException{return AccountServlet.db(config);}
    Connection node(int node)throws Exception{
        String prefix=node==1?"db.":"storage.node2.db.";
        Properties p=new Properties();p.setProperty("user",cluster.required(prefix+"user"));p.setProperty("password",cluster.required(prefix+"password"));
        p.setProperty("connectTimeout","3000");p.setProperty("socketTimeout",Integer.toString(AccountServlet.databaseSocketTimeout(config)));return ReplicaAuthority.checked(DriverManager.getConnection(AccountServlet.jdbcUrl(config,prefix+"url"),p),config);
    }
    static boolean atomicRuntimeReady(AtomicFileStorage storage,StorageCluster cluster){
        try{String identity=Files.readString(Path.of(cluster.required("storage.objectStore")).resolve("identity")).trim();storage.database.runtimeProbe(identity);return true;}
        catch(Exception unavailable){return false;}
    }
    boolean runtimeReady(){return !closing&&atomic!=null&&atomicRuntimeReady(atomic,cluster)&&!closing;}
    void ensureReady(){if(!ready||closing)throw StorageFailure.unavailable();cluster.probes();}
    static byte[] hash(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception failure){throw new IllegalStateException(failure);}}
    static String operationId(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();return id;}catch(Exception invalid){throw new StorageFailure(400,"INVALID_OPERATION","操作标识无效，请刷新后重试。");}}
    static boolean fits(long used,long reserved,long added){return used>=0&&reserved>=0&&added>=0&&used<=WorkspaceServlet.QUOTA&&reserved<=WorkspaceServlet.QUOTA-used&&added<=WorkspaceServlet.QUOTA-used-reserved;}

    static String lockUser(Connection c,long user)throws SQLException{
        String generation;
        try(PreparedStatement q=c.prepareStatement("SELECT generation FROM users WHERE id=? AND active=1 FOR UPDATE")){q.setLong(1,user);try(ResultSet r=q.executeQuery()){if(!r.next())throw StorageFailure.unavailable();generation=r.getString(1);}}
        try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO file_storage_users(user_id,generation,used_bytes) SELECT ?,?,COALESCE(SUM(size_bytes),0) FROM library_files WHERE user_id=?")){q.setLong(1,user);q.setString(2,generation);q.setLong(3,user);q.executeUpdate();}
        try(PreparedStatement q=c.prepareStatement("SELECT generation,active_op FROM file_storage_users WHERE user_id=? FOR UPDATE")){q.setLong(1,user);try(ResultSet r=q.executeQuery()){if(!r.next()||!generation.equals(r.getString(1)))throw StorageFailure.unavailable();if(r.getString(2)!=null)throw StorageFailure.pending();}}
        return generation;
    }

    private interface RequestWork<T>{T run()throws Exception;}
    private <T>T request(RequestWork<T> work)throws Exception{
        if(replicated&&!replicaAdmission.tryAcquire())throw StorageFailure.pending();
        lifecycle.readLock().lock();
        try{
            if(closing)throw StorageFailure.unavailable();
            T result=work.run();
            // Generated-image saves also use this path outside ClusterFilter.
            // A failed post-commit check leaves the operation outcome uncertain.
            if(replicated)ensureReady();
            return result;
        }
        finally{lifecycle.readLock().unlock();if(replicated)replicaAdmission.release();}
    }
    ObjectNode list(long user,JsonNode input)throws Exception{return request(()->atomic!=null?atomic.list(user,input):listNow(user,input));}
    ObjectNode read(long user,String id)throws Exception{return request(()->atomic!=null?atomic.read(user,id):readNow(user,id));}
    AtomicFileStorage.BinaryFile readBinary(long user,String id)throws Exception{return request(()->{
        if(atomic!=null)return atomic.readBinary(user,id);ObjectNode old=readNow(user,id);return new AtomicFileStorage.BinaryFile(old.path("name").asText(),old.path("mime").asText(),Base64.getDecoder().decode(old.path("data").asText()));
    });}
    ObjectNode trash(long user,String id,boolean restore)throws Exception{
        if(atomic!=null)throw new StorageFailure(400,"INVALID_OPERATION","A stable operation identity is required.");
        return request(()->trashNow(user,id,restore));
    }
    ObjectNode trash(long user,String op,String id,boolean restore)throws Exception{return request(()->atomic!=null?atomic.trash(user,op,id,restore):trashNow(user,id,restore));}
    ObjectNode upload(HttpServletRequest req,long user,String op,List<Upload> files)throws Exception{return request(()->atomic!=null?atomic.upload(req,user,op,files):uploadNow(req,user,op,files));}
    ObjectNode reserveUpload(HttpServletRequest req,long user,String op,JsonNode metadata)throws Exception{return request(()->{
        if(atomic==null)throw new StorageFailure(400,"ATOMIC_REQUIRED","Durable reservations require atomic storage");
        return atomic.reserve(user,op,metadata,cluster.ingress(req));
    });}
    ObjectNode uploadReserved(HttpServletRequest req,long user,String op,List<Upload> files)throws Exception{return request(()->{
        if(atomic==null)throw new StorageFailure(400,"ATOMIC_REQUIRED","Durable reservations require atomic storage");
        return atomic.complete(req,user,op,files);
    });}
    ObjectNode cancelUpload(long user,String op)throws Exception{return request(()->{
        if(atomic==null)throw new StorageFailure(400,"ATOMIC_REQUIRED","Durable reservations require atomic storage");return atomic.cancel(user,op);
    });}
    ObjectNode delete(long user,String op,String id)throws Exception{return request(()->atomic!=null?atomic.delete(user,op,id):deleteNow(user,op,id));}
    ObjectNode status(long user,String id)throws Exception{return request(()->atomic!=null?atomic.status(user,id):statusNow(user,id));}
    ObjectNode summary(long user,String id)throws Exception{return request(()->{if(atomic==null)throw StorageFailure.unavailable();return atomic.summary(user,id);});}

    private ObjectNode listNow(long user,JsonNode input)throws Exception{
        ensureReady();try(Connection c=primary()){c.setAutoCommit(false);try{lockUser(c,user);ObjectNode result=FileLibrary.listPage(c,user,input);c.commit();return result;}catch(Exception failure){c.rollback();throw failure;}}
    }
    private ObjectNode readNow(long user,String id)throws Exception{
        ensureReady();try(Connection c=primary()){c.setAutoCommit(false);try{
            String generation=lockUser(c,user),name,mime;byte[] content;int node;
            try(PreparedStatement q=c.prepareStatement("SELECT name,mime,content,storage_node_id FROM library_files WHERE id=? AND user_id=? AND owner_generation=? AND deleted_at IS NULL")){
                q.setString(1,id);q.setLong(2,user);q.setString(3,generation);try(ResultSet r=q.executeQuery()){if(!r.next())throw new StorageFailure(404,"FILE_NOT_FOUND","文件不存在。");name=r.getString(1);mime=r.getString(2);content=r.getBytes(3);node=r.getInt(4);}}
            if(content==null)try(Connection target=node(node);PreparedStatement q=target.prepareStatement("SELECT content,sha256,size_bytes FROM file_blobs WHERE id=? AND user_id=? AND owner_generation=?")){
                q.setString(1,id);q.setLong(2,user);q.setString(3,generation);try(ResultSet r=q.executeQuery()){if(!r.next())throw StorageFailure.unavailable();content=r.getBytes(1);if(content.length!=r.getInt(3)||!MessageDigest.isEqual(hash(content),r.getBytes(2)))throw StorageFailure.unavailable();}}
            c.commit();return ModelGateway.JSON.createObjectNode().put("name",name).put("mime",mime).put("data",Base64.getEncoder().encodeToString(content));
        }catch(Exception failure){c.rollback();throw failure;}}}
    private ObjectNode trashNow(long user,String id,boolean restore)throws Exception{
        ensureReady();try(Connection c=primary()){c.setAutoCommit(false);try{
            String generation=lockUser(c,user);
            try(PreparedStatement q=c.prepareStatement("SELECT id FROM library_files WHERE id=? AND user_id=? AND owner_generation=? FOR UPDATE")){q.setString(1,id);q.setLong(2,user);q.setString(3,generation);try(ResultSet r=q.executeQuery()){if(!r.next())throw new StorageFailure(404,"FILE_NOT_FOUND","文件不存在。");}}
            try(PreparedStatement q=c.prepareStatement("UPDATE library_files SET deleted_at="+(restore?"NULL":"COALESCE(deleted_at,CURRENT_TIMESTAMP)")+" WHERE id=? AND user_id=? AND owner_generation=?")){q.setString(1,id);q.setLong(2,user);q.setString(3,generation);q.executeUpdate();}
            c.commit();return ModelGateway.JSON.createObjectNode().put("ok",true);
        }catch(Exception failure){c.rollback();throw failure;}}}

    private ObjectNode uploadNow(HttpServletRequest request,long user,String operation,List<Upload> files)throws Exception{
        ensureReady();operation=operationId(operation);if(files.isEmpty())throw new StorageFailure(400,"EMPTY_UPLOAD","文件为空。");
        long total=0;ArrayNode plan=ModelGateway.JSON.createArrayNode();
        for(Upload file:files){total=Math.addExact(total,file.content.length);plan.add(file.metadata());}
        // Generated random IDs are not part of the request fingerprint.
        ArrayNode fingerprint=plan.deepCopy();for(JsonNode file:fingerprint)((ObjectNode)file).remove("id");
        byte[] digest=hash(("UPLOAD:"+fingerprint.toString()).getBytes(StandardCharsets.UTF_8));
        Operation admitted=reserve(user,operation,digest,plan,total,cluster.ingress(request),cluster.probes(),null);
        if("COMMITTED".equals(admitted.state))return admitted.result();
        execute(admitted,connections->{Connection primary=connections.get(1),blob=connections.get(admitted.node);
            for(Upload file:files){
                try(PreparedStatement q=blob.prepareStatement("INSERT INTO file_blobs(id,user_id,owner_generation,content,sha256,size_bytes) VALUES(?,?,?,?,?,?)")){q.setString(1,file.id);q.setLong(2,user);q.setString(3,admitted.generation);q.setBytes(4,file.content);q.setBytes(5,file.digest);q.setInt(6,file.content.length);q.executeUpdate();}
                try(PreparedStatement q=primary.prepareStatement("INSERT INTO library_files(id,user_id,name,mime,size_bytes,content,storage_node_id,owner_generation) VALUES(?,?,?,?,?,NULL,?,?)")){q.setString(1,file.id);q.setLong(2,user);q.setString(3,file.name);q.setString(4,file.mime);q.setInt(5,file.content.length);q.setInt(6,admitted.node);q.setString(7,admitted.generation);q.executeUpdate();}
            }
            try(PreparedStatement q=primary.prepareStatement("UPDATE file_storage_users SET used_bytes=used_bytes+?,reserved_bytes=reserved_bytes-? WHERE user_id=? AND generation=? AND active_op=? AND reserved_bytes>=?")){q.setLong(1,admitted.logical);q.setLong(2,admitted.logical);q.setLong(3,user);q.setString(4,admitted.generation);q.setString(5,admitted.id);q.setLong(6,admitted.logical);if(q.executeUpdate()!=1)throw new SQLException("Quota reservation changed.");}
        });return load(operation).result();
    }

    private ObjectNode deleteNow(long user,String operation,String id)throws Exception{
        ensureReady();operation=operationId(operation);
        byte[] digest=hash(("DELETE:"+id).getBytes(StandardCharsets.UTF_8));
        Operation admitted=reserve(user,operation,digest,null,0,1,cluster.probes(),id);
        if("COMMITTED".equals(admitted.state))return admitted.result();
        JsonNode file=ModelGateway.JSON.readTree(admitted.files).get(0);
        execute(admitted,connections->{
            if(!file.path("legacy").asBoolean())try(PreparedStatement q=connections.get(admitted.node).prepareStatement("DELETE FROM file_blobs WHERE id=? AND user_id=? AND owner_generation=?")){q.setString(1,id);q.setLong(2,user);q.setString(3,admitted.generation);if(q.executeUpdate()!=1)throw new SQLException("File body missing.");}
            try(PreparedStatement q=connections.get(1).prepareStatement("DELETE FROM library_files WHERE id=? AND user_id=? AND owner_generation=? AND deleted_at IS NOT NULL")){q.setString(1,id);q.setLong(2,user);q.setString(3,admitted.generation);if(q.executeUpdate()!=1)throw new SQLException("File state changed.");}
            try(PreparedStatement q=connections.get(1).prepareStatement("UPDATE file_storage_users SET used_bytes=used_bytes-? WHERE user_id=? AND generation=? AND active_op=? AND used_bytes>=?")){q.setLong(1,admitted.logical);q.setLong(2,user);q.setString(3,admitted.generation);q.setString(4,admitted.id);q.setLong(5,admitted.logical);if(q.executeUpdate()!=1)throw new SQLException("Quota state changed.");}
        });return load(operation).result();
    }

    private Operation reserve(long user,String id,byte[] digest,ArrayNode files,long total,int preferred,Map<Integer,StorageCluster.Probe> probes,String deleting)throws Exception{
        try(Connection c=primary()){c.setAutoCommit(false);try{
            Operation previous=find(c,id);
            if(previous!=null){
                try(PreparedStatement owner=c.prepareStatement("SELECT generation FROM users WHERE id=? AND active=1")){owner.setLong(1,user);try(ResultSet r=owner.executeQuery()){if(!r.next()||!previous.generation.equals(r.getString(1)))throw new StorageFailure(409,"OPERATION_CONFLICT","操作标识属于旧账户。");}}
                try(PreparedStatement q=c.prepareStatement("SELECT request_hash FROM file_storage_operations WHERE op_id=? AND user_id=?")){q.setString(1,id);q.setLong(2,user);try(ResultSet r=q.executeQuery()){if(!r.next()||!MessageDigest.isEqual(digest,r.getBytes(1)))throw new StorageFailure(409,"OPERATION_CONFLICT","操作标识已用于其他请求。");}}
                c.rollback();if("COMMITTED".equals(previous.state))return previous;
                if("ABORTED".equals(previous.state))throw new StorageFailure(409,"OPERATION_ABORTED","操作未完成，请重新提交。");throw StorageFailure.pending();
            }
            String generation=lockUser(c,user);int selected=1;long physical=0,primaryPhysical=0;Map<Integer,Long> replicaBudgets=Map.of();
            if(deleting!=null){
                try(PreparedStatement q=c.prepareStatement("SELECT size_bytes,storage_node_id,content IS NOT NULL FROM library_files WHERE id=? AND user_id=? AND owner_generation=? AND deleted_at IS NOT NULL FOR UPDATE")){
                    q.setString(1,deleting);q.setLong(2,user);q.setString(3,generation);try(ResultSet r=q.executeQuery()){if(!r.next())throw new StorageFailure(404,"FILE_NOT_FOUND","文件不存在或尚未移入回收箱。");total=r.getLong(1);selected=r.getInt(2);files=ModelGateway.JSON.createArrayNode();files.addObject().put("id",deleting).put("legacy",r.getBoolean(3));}}
            }else{
                try(PreparedStatement q=c.prepareStatement("SELECT used_bytes,reserved_bytes FROM file_storage_users WHERE user_id=?")){q.setLong(1,user);try(ResultSet r=q.executeQuery()){r.next();if(!fits(r.getLong(1),r.getLong(2),total))throw new StorageFailure(413,"QUOTA_EXCEEDED","账户文件库已达到 2 GB 上限（含回收箱及待完成上传）。");}}
                Map<Integer,Long> reservations=new HashMap<>();
                if(multiPhysical){
                    for(int physicalNode:new TreeSet<>(cluster.physicalNodeIds()))try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO file_storage_physical_nodes(node_id) VALUES(?)")){q.setInt(1,physicalNode);q.executeUpdate();}
                }
                String capacityTable=multiPhysical?"file_storage_physical_nodes":"file_storage_nodes";
                try(PreparedStatement q=c.prepareStatement("SELECT node_id,reserved_bytes FROM "+capacityTable+" ORDER BY node_id FOR UPDATE");ResultSet r=q.executeQuery()){while(r.next())reservations.put(r.getInt(1),r.getLong(2));}
                probes=cluster.probes();
                if(multiPhysical) {
                    replicaBudgets=ReplicaCapacity.budgets(probes,reservations,total,System.currentTimeMillis(),cluster.physicalNodeIds());
                    selected=preferred; // Logical shard is independent of the physical host.
                }else if(replicated) {
                    Map<Integer,Long> budgets=ReplicaCapacity.budgets(probes,reservations,total,System.currentTimeMillis());
                    // The sole surviving physical node must also be the ingress;
                    // logical data still exists in both schemas on that node.
                    if(!budgets.containsKey(preferred))throw StorageFailure.unavailable();
                    selected=preferred;physical=budgets.get(selected);primaryPhysical=budgets.getOrDefault(3-selected,0L);
                }else {
                    selected=0;for(int candidate:new int[]{preferred,3-preferred}){
                        StorageCluster.Probe probe=probes.get(candidate),control=probes.get(1);long now=System.currentTimeMillis();
                        // A remote body still needs canonical metadata and decision headroom.
                        if(reservations.containsKey(candidate)&&probe.admits(total,reservations.get(candidate),now)
                                &&(candidate==1||control.admits(1,reservations.getOrDefault(1,Long.MAX_VALUE),now))){
                            selected=candidate;physical=probe.budget(total);primaryPhysical=candidate==1?0:control.budget(1);break;
                        }
                    }
                }
                if(selected==0)throw new StorageFailure(507,"INSUFFICIENT_STORAGE","服务器存储空间不足，请稍后再试。");
                if(multiPhysical){
                    for(var budget:replicaBudgets.entrySet())try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_physical_nodes SET reserved_bytes=reserved_bytes+? WHERE node_id=?")){q.setLong(1,budget.getValue());q.setInt(2,budget.getKey());if(q.executeUpdate()!=1)throw new SQLException("Physical replica storage node missing.");}
                }else{
                    try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_nodes SET reserved_bytes=reserved_bytes+? WHERE node_id=?")){q.setLong(1,physical);q.setInt(2,selected);if(q.executeUpdate()!=1)throw new SQLException("Storage node missing.");}
                    if(primaryPhysical>0)try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_nodes SET reserved_bytes=reserved_bytes+? WHERE node_id=?")){q.setLong(1,primaryPhysical);q.setInt(2,3-selected);if(q.executeUpdate()!=1)throw new SQLException("Peer storage node missing.");}
                }
                try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_users SET reserved_bytes=reserved_bytes+? WHERE user_id=?")){q.setLong(1,total);q.setLong(2,user);q.executeUpdate();}
            }
            try(PreparedStatement q=c.prepareStatement("INSERT INTO file_storage_operations(op_id,user_id,user_generation,request_hash,kind,node_id,state,logical_bytes,physical_bytes,primary_physical_bytes,files_json) VALUES(?,?,?,?,?,?,'PENDING',?,?,?,?)")){
                q.setString(1,id);q.setLong(2,user);q.setString(3,generation);q.setBytes(4,digest);q.setString(5,deleting==null?"UPLOAD":"DELETE");q.setInt(6,selected);q.setLong(7,total);q.setLong(8,physical);q.setLong(9,primaryPhysical);q.setString(10,files.toString());q.executeUpdate();}
            for(var budget:replicaBudgets.entrySet())try(PreparedStatement q=c.prepareStatement("INSERT INTO file_storage_replica_reservations(op_id,node_id,reserved_bytes) VALUES(?,?,?)")){q.setString(1,id);q.setInt(2,budget.getKey());q.setLong(3,budget.getValue());q.executeUpdate();}
            try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_users SET active_op=? WHERE user_id=? AND generation=? AND active_op IS NULL")){q.setString(1,id);q.setLong(2,user);q.setString(3,generation);if(q.executeUpdate()!=1)throw new SQLException("User gate changed.");}
            executing.add(id);c.commit();return load(id);
        }catch(Exception failure){executing.remove(id);c.rollback();throw failure;}}
    }

    private void execute(Operation operation,XaTransactions.Work work)throws Exception{
        if(!executing.contains(operation.id))throw StorageFailure.pending();
        try{
            XaTransactions.Outcome outcome=transactions.execute(operation.id,operation.participants(),connections->{work.run(connections);for(Connection c:connections.values())try(PreparedStatement q=c.prepareStatement("INSERT INTO file_storage_receipts(op_id) VALUES(?)")){q.setString(1,operation.id);q.executeUpdate();}});
            if(outcome==XaTransactions.Outcome.ABORTED&&confirmedAbort(operation)){finish(operation,false);throw new StorageFailure(503,"OPERATION_ABORTED","服务器故障，请等待恢复");}
            if(outcome==XaTransactions.Outcome.COMMITTED&&allReceipts(operation)){finish(operation,true);return;}
            uncertain.add(operation.id);ready=false;throw StorageFailure.pending();
        }catch(StorageFailure failure){
            if(!"OPERATION_ABORTED".equals(failure.code)){uncertain.add(operation.id);ready=false;}
            throw failure;
        }catch(Exception failure){uncertain.add(operation.id);ready=false;throw StorageFailure.pending();}
        finally{executing.remove(operation.id);}
    }

    private boolean receipt(int node,String operation)throws Exception{try(Connection c=node(node);PreparedStatement q=c.prepareStatement("SELECT op_id FROM file_storage_receipts WHERE op_id=?")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){return r.next();}}}
    private boolean allReceipts(Operation operation)throws Exception{for(int node:operation.participants())if(!receipt(node,operation.id))return false;return true;}
    private boolean confirmedAbort(Operation operation)throws Exception{
        for(int node:operation.participants())if(receipt(node,operation.id))return false;
        try(Connection c=primary();PreparedStatement q=c.prepareStatement("SELECT terminal FROM file_storage_branches WHERE op_id=?")){q.setString(1,operation.id);try(ResultSet r=q.executeQuery()){while(r.next())if(!"ABORTED".equals(r.getString(1)))return false;}}
        return true;
    }
    private void finish(Operation operation,boolean committed)throws Exception{
        try(Connection c=primary()){c.setAutoCommit(false);try{
            try(PreparedStatement q=c.prepareStatement("SELECT active_op FROM file_storage_users WHERE user_id=? AND generation=? FOR UPDATE")){q.setLong(1,operation.user);q.setString(2,operation.generation);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("User gate missing.");if(!operation.id.equals(r.getString(1))){Operation current=find(c,operation.id);if(current!=null&&current.state.equals(committed?"COMMITTED":"ABORTED")){c.rollback();return;}throw new SQLException("Unexpected user gate.");}}}
            if(!committed&&"UPLOAD".equals(operation.kind))try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_users SET reserved_bytes=reserved_bytes-? WHERE user_id=? AND reserved_bytes>=?")){q.setLong(1,operation.logical);q.setLong(2,operation.user);q.setLong(3,operation.logical);if(q.executeUpdate()!=1)throw new SQLException("Quota release mismatch.");}
            for(int node:new int[]{1,2}){
                long amount=node==operation.node?operation.physical:operation.primaryPhysical;
                if(amount>0)try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_nodes SET reserved_bytes=reserved_bytes-? WHERE node_id=? AND reserved_bytes>=?")){q.setLong(1,amount);q.setInt(2,node);q.setLong(3,amount);if(q.executeUpdate()!=1)throw new SQLException("Capacity release mismatch.");}
            }
            if(multiPhysical){
                Map<Integer,Long> held=new TreeMap<>();
                try(PreparedStatement q=c.prepareStatement("SELECT node_id,reserved_bytes FROM file_storage_replica_reservations WHERE op_id=? ORDER BY node_id FOR UPDATE")){q.setString(1,operation.id);try(ResultSet r=q.executeQuery()){while(r.next())held.put(r.getInt(1),r.getLong(2));}}
                for(var reservation:held.entrySet())try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_physical_nodes SET reserved_bytes=reserved_bytes-? WHERE node_id=? AND reserved_bytes>=?")){q.setLong(1,reservation.getValue());q.setInt(2,reservation.getKey());q.setLong(3,reservation.getValue());if(q.executeUpdate()!=1)throw new SQLException("Replica capacity release mismatch.");}
                try(PreparedStatement q=c.prepareStatement("DELETE FROM file_storage_replica_reservations WHERE op_id=?")){q.setString(1,operation.id);q.executeUpdate();}
            }
            try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_operations SET state=? WHERE op_id=? AND state='PENDING'")){q.setString(1,committed?"COMMITTED":"ABORTED");q.setString(2,operation.id);if(q.executeUpdate()!=1)throw new SQLException("Operation finalization mismatch.");}
            try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_users SET active_op=NULL WHERE user_id=? AND generation=? AND active_op=?")){q.setLong(1,operation.user);q.setString(2,operation.generation);q.setString(3,operation.id);if(q.executeUpdate()!=1)throw new SQLException("Gate release mismatch.");}
            c.commit();uncertain.remove(operation.id);
        }catch(Exception failure){c.rollback();throw failure;}}
    }

    private Operation load(String id)throws Exception{try(Connection c=primary()){Operation value=find(c,id);if(value==null)throw StorageFailure.unavailable();return value;}}
    private Operation find(Connection c,String id)throws SQLException{
        try(PreparedStatement q=c.prepareStatement("SELECT user_id,user_generation,kind,node_id,state,logical_bytes,physical_bytes,primary_physical_bytes,files_json,tx_uid FROM file_storage_operations WHERE op_id=?")){q.setString(1,id);try(ResultSet r=q.executeQuery()){if(!r.next())return null;Operation op=new Operation();op.id=id;op.user=r.getLong(1);op.generation=r.getString(2);op.kind=r.getString(3);op.node=r.getInt(4);op.state=r.getString(5);op.logical=r.getLong(6);op.physical=r.getLong(7);op.primaryPhysical=r.getLong(8);op.files=r.getString(9);op.uid=r.getString(10);return op;}}
    }
    private ObjectNode statusNow(long user,String id)throws Exception{
        ensureReady();try(Connection c=primary()){
            Operation op=find(c,operationId(id));
            if(op==null||op.user!=user)throw new StorageFailure(404,"OPERATION_NOT_FOUND","操作不存在。");
            try(PreparedStatement q=c.prepareStatement("SELECT generation FROM users WHERE id=? AND active=1")){q.setLong(1,user);try(ResultSet r=q.executeQuery()){if(!r.next()||!op.generation.equals(r.getString(1)))throw new StorageFailure(404,"OPERATION_NOT_FOUND","操作不存在。");}}
            return op.result();
        }
    }

    public void uid(String operation,String uid)throws Exception{try(Connection c=primary();PreparedStatement q=c.prepareStatement("UPDATE file_storage_operations SET tx_uid=? WHERE op_id=? AND state='PENDING' AND tx_uid IS NULL")){q.setString(1,uid);q.setString(2,operation);if(q.executeUpdate()!=1)throw new SQLException("Transaction already assigned.");}}
    public void started(String operation,int node,Xid xid)throws Exception{try(Connection c=primary();PreparedStatement q=c.prepareStatement("INSERT INTO file_storage_branches(op_id,node_id,format_id,gtrid,bqual) VALUES(?,?,?,?,?)")){q.setString(1,operation);q.setInt(2,node);q.setInt(3,xid.getFormatId());q.setBytes(4,xid.getGlobalTransactionId());q.setBytes(5,xid.getBranchQualifier());q.executeUpdate();}}
    private String branchOperation(int node,Xid xid)throws Exception{try(Connection c=primary();PreparedStatement q=c.prepareStatement("SELECT op_id FROM file_storage_branches WHERE node_id=? AND format_id=? AND gtrid=? AND bqual=?")){bindXid(q,node,xid,1);try(ResultSet r=q.executeQuery()){return r.next()?r.getString(1):null;}}}
    public boolean committed(int node,Xid xid)throws Exception{String op=branchOperation(node,xid);return op!=null&&receipt(node,op);}
    public void ended(int node,Xid xid,String terminal)throws Exception{try(Connection c=primary();PreparedStatement q=c.prepareStatement("UPDATE file_storage_branches SET terminal=? WHERE node_id=? AND format_id=? AND gtrid=? AND bqual=? AND (terminal IS NULL OR terminal=?)")){q.setString(1,terminal);bindXid(q,node,xid,2);q.setString(6,terminal);if(q.executeUpdate()!=1){try(PreparedStatement check=c.prepareStatement("SELECT terminal FROM file_storage_branches WHERE node_id=? AND format_id=? AND gtrid=? AND bqual=?")){bindXid(check,node,xid,1);try(ResultSet r=check.executeQuery()){if(!r.next()||!terminal.equals(r.getString(1)))throw new SQLException("Conflicting XA branch outcome.");}}}}}
    private static void bindXid(PreparedStatement q,int node,Xid xid,int first)throws SQLException{q.setInt(first,node);q.setInt(first+1,xid.getFormatId());q.setBytes(first+2,xid.getGlobalTransactionId());q.setBytes(first+3,xid.getBranchQualifier());}

    synchronized void recover(){
        if(closing||!executing.isEmpty())return;
        if(atomic!=null){
            ready=runtimeReady();
            return; // InnoDB owns crash rollback; never scan or abort another JVM's work.
        }
        try{
            cluster.probes();for(int node:sources.keySet())try(Connection c=node(node);PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM file_storage_receipts");ResultSet r=q.executeQuery()){r.next();}
            transactions.recover();List<String> pending=new ArrayList<>();
            try(Connection c=primary();PreparedStatement q=c.prepareStatement("SELECT op_id FROM file_storage_operations WHERE state='PENDING' ORDER BY created_at");ResultSet r=q.executeQuery()){while(r.next())pending.add(r.getString(1));}
            boolean unresolved=false;
            for(String id:pending){if(executing.contains(id))continue;Operation op=load(id);
                if(allReceipts(op)){finish(op,true);continue;}
                boolean noReceipt=true;for(int node:op.participants())if(receipt(node,id))noReceipt=false;
                // The exclusive process lock excludes a surviving old coordinator. Narayana's
                // durable decision and live transaction table are checked before presumed abort.
                if(noReceipt&&!transactions.isLive(op.uid)&&!transactions.hasDecisionLog(op.uid)){
                    List<Branch> branches=new ArrayList<>();
                    try(Connection c=primary();PreparedStatement q=c.prepareStatement("SELECT node_id,format_id,gtrid,bqual,terminal FROM file_storage_branches WHERE op_id=?")){q.setString(1,id);try(ResultSet r=q.executeQuery()){while(r.next())if(!"ABORTED".equals(r.getString(5)))branches.add(new Branch(r.getInt(1),r.getInt(2),r.getBytes(3),r.getBytes(4)));}}
                    for(Branch branch:branches)transactions.abortOrphan(branch.node,branch);
                    if(confirmedAbort(op)){finish(op,false);continue;}
                }
                unresolved=true;
            }
            // A live transaction can create a transient decision log while the
            // scan is running. Its own completion path decides whether to close
            // readiness; do not mislabel that normal in-flight work as failure.
            if(!executing.isEmpty())return;
            ready=!closing&&!unresolved&&uncertain.isEmpty()&&!transactions.hasUnresolvedDecisionLogs();
        }catch(Exception failure){ready=false;}
    }
    public void close()throws Exception{
        closing=true;ready=false;recovery.shutdownNow();
        // If drainage times out, retain the process lock. A new coordinator must not
        // overlap JDBC work that can still finish in the old classloader.
        if(!recovery.awaitTermination(20,TimeUnit.SECONDS)||!lifecycle.writeLock().tryLock(20,TimeUnit.SECONDS))return;
        try{
            if(!executing.isEmpty())return;
            if(atomic!=null){
                if(!atomic.database.closeAndDrain())return;
                atomicProcessLock.release();atomicLockChannel.close();
            }else transactions.close();
        }finally{lifecycle.writeLock().unlock();}
    }
    private static final class Branch implements Xid{
        final int node,format;final byte[] global,branch;
        Branch(int node,int format,byte[] global,byte[] branch){this.node=node;this.format=format;this.global=global;this.branch=branch;}
        public int getFormatId(){return format;}public byte[] getGlobalTransactionId(){return global;}public byte[] getBranchQualifier(){return branch;}
    }
}
