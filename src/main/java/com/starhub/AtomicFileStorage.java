package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Immutable segments receive individual confirmations; one small commit publishes the complete file batch. */
final class AtomicFileStorage {
    static final long MAX_OPERATION_BYTES=64L*1024*1024;
    final AtomicDatabase database;final AtomicGuestStorage guest;final ProviderOperations providers;final AtomicPayloads payloads;private final StorageCluster cluster;
    AtomicFileStorage(FileStorage owner)throws Exception {cluster=owner.cluster;database=new AtomicDatabase(owner.config);payloads=new AtomicPayloads(database,owner.config);database.verifyDrainedSchema();guest=new AtomicGuestStorage(database,owner.config,this::transientCapacity);providers=new ProviderOperations(database,cluster,owner.config);}
    AtomicFileStorage(AtomicDatabase database,StorageCluster cluster){this.cluster=cluster;this.database=database;payloads=new AtomicPayloads(database,database.config);guest=null;providers=null;}
    private static byte[] digest(String kind,String payload){return FileStorage.hash((kind+":"+payload).getBytes(StandardCharsets.UTF_8));}
    private String user(Connection c,long id)throws SQLException{return FileStorage.lockUser(c,id);}
    ObjectNode list(long id,JsonNode input)throws Exception {
        return database.run(UUID.randomUUID().toString(),c->{
            String generation=user(c,id);ObjectNode result=FileLibrary.listPage(c,id,input);
            try(PreparedStatement q=c.prepareStatement("SELECT used_bytes,reserved_bytes FROM file_storage_users WHERE user_id=? AND generation=?")){
                q.setLong(1,id);q.setString(2,generation);try(ResultSet r=q.executeQuery()){if(!r.next())throw StorageFailure.unavailable();result.put("storedBytes",r.getLong(1)).put("reservedBytes",r.getLong(2)).put("used",Math.addExact(r.getLong(1),r.getLong(2)));}
            }
            ArrayNode providerPending=result.putArray("pendingImageGenerations");
            try(PreparedStatement q=c.prepareStatement("SELECT operation_id,logical_reserved,state FROM starhub_provider_operations WHERE account_user_id=? AND account_generation=? AND logical_reserved>0 AND state IN ('CLAIMED','SENT','UNKNOWN') ORDER BY created_millis,operation_id")){
                q.setLong(1,id);q.setString(2,generation);try(ResultSet r=q.executeQuery()){while(r.next())providerPending.addObject().put("operationId",r.getString(1)).put("reservedBytes",r.getLong(2)).put("state",r.getString(3));}
            }
            long pendingTotal;try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM file_storage_operations WHERE user_id=? AND user_generation=? AND state IN ('RESERVED','CANCELLING')")){q.setLong(1,id);q.setString(2,generation);try(ResultSet r=q.executeQuery()){r.next();pendingTotal=r.getLong(1);}}
            int requested=FileLibrary.positiveInteger(input,"pendingPage",1,Integer.MAX_VALUE);long pages=Math.max(1,(pendingTotal+99)/100);int page=(int)Math.min(requested,pages);
            result.put("pendingTotal",pendingTotal).put("pendingPage",page).put("pendingHasNext",page<pages);ArrayNode pending=result.putArray("pendingUploads");
            try(PreparedStatement q=c.prepareStatement("SELECT op_id,logical_bytes,files_json FROM file_storage_operations WHERE user_id=? AND user_generation=? AND state IN ('RESERVED','CANCELLING') ORDER BY created_at,op_id LIMIT 100 OFFSET ?")){
                q.setLong(1,id);q.setString(2,generation);q.setLong(3,((long)page-1)*100);try(ResultSet r=q.executeQuery()){while(r.next()){ObjectNode item=pending.addObject().put("operationId",r.getString(1)).put("bytes",r.getLong(2));item.set("files",ModelGateway.JSON.readTree(r.getString(3)));}}
            }
            return result;
        });
    }
    record BinaryFile(String name,String mime,byte[] bytes){}
    ObjectNode read(long id,String file)throws Exception {
        BinaryFile content=readBinary(id,file);return ModelGateway.JSON.createObjectNode().put("name",content.name).put("mime",content.mime).put("data",Base64.getEncoder().encodeToString(content.bytes));
    }
    BinaryFile readBinary(long id,String file)throws Exception {
        return database.run(UUID.randomUUID().toString(),c->{
            String generation=user(c,id);byte[] content;String name,mime;int node;long size;
            try(PreparedStatement q=c.prepareStatement("SELECT name,mime,content,storage_node_id,size_bytes FROM library_files WHERE id=? AND user_id=? AND owner_generation=? AND deleted_at IS NULL")){
                q.setString(1,file);q.setLong(2,id);q.setString(3,generation);
                try(ResultSet r=q.executeQuery()){if(!r.next())throw missing();name=r.getString(1);mime=r.getString(2);content=r.getBytes(3);node=r.getInt(4);size=r.getLong(5);}
            }
            ChunkedPayloadStore.Plan chunked=content==null?filePlan(c,file):null;
            if(chunked!=null){payloads.owner(chunked,id+":"+generation,"FILE");if(!chunked.id().equals(file))throw StorageFailure.unavailable();if(!"PUBLISHED".equals(ChunkedPayloadStore.state(c,chunked)))throw StorageFailure.unavailable();content=ChunkedPayloadStore.readBytes(c,chunked,GeneratedImages.MAX_IMAGE);}
            if(content==null)try(PreparedStatement q=c.prepareStatement("SELECT content,sha256,size_bytes FROM "+database.table(node,"file_blobs")+" WHERE id=? AND user_id=? AND owner_generation=?")){
                q.setString(1,file);q.setLong(2,id);q.setString(3,generation);try(ResultSet r=q.executeQuery()){
                    if(!r.next())throw StorageFailure.unavailable();content=r.getBytes(1);
                    if(content==null||content.length!=r.getLong(3)||!MessageDigest.isEqual(FileStorage.hash(content),r.getBytes(2)))throw StorageFailure.unavailable();
                }
            }
            if(content.length!=size)throw StorageFailure.unavailable();
            return new BinaryFile(name,mime,content);
        });
    }
    ObjectNode upload(HttpServletRequest request,long id,String op,List<FileStorage.Upload> files)throws Exception {
        if(database.inside())throw new SQLException("Durable reservation and content require separate confirmed transactions");
        ArrayNode metadata=metadata(files);ObjectNode reserved=reserve(id,op,metadata,cluster.ingress(request));
        if("COMMITTED".equals(reserved.path("state").asText()))return reserved;
        return complete(request,id,op,files);
    }
    private static ArrayNode metadata(List<FileStorage.Upload> files){ArrayNode result=ModelGateway.JSON.createArrayNode();for(var file:files){ObjectNode value=file.metadata();value.remove("id");result.add(value);}return result;}
    private record Plan(ArrayNode fingerprint,long bytes) { byte[] hash(){return digest("UPLOAD",fingerprint.toString());} }
    private static Plan plan(JsonNode metadata){
        if(!metadata.isArray()||metadata.isEmpty()||metadata.size()>64)throw new StorageFailure(400,"INVALID_FILE","文件计划无效。");
        long total=0;ArrayNode normalized=ModelGateway.JSON.createArrayNode();
        for(JsonNode file:metadata){
            String name=file.path("name").asText(),mime=file.path("mime").asText(),sha=file.path("sha256").asText();JsonNode length=file.path("size");
            if(name.isBlank()||name.length()>255||name.indexOf('/')>=0||name.indexOf('\\')>=0||!WorkspaceServlet.TYPES.contains(mime)||!length.isIntegralNumber()||!length.canConvertToLong()||length.longValue()<1||length.longValue()>GeneratedImages.MAX_IMAGE)
                throw new StorageFailure(400,"INVALID_FILE","文件类型或大小不受支持。");
            try{byte[] hash=Base64.getDecoder().decode(sha);if(hash.length!=32||!Base64.getEncoder().encodeToString(hash).equals(sha))throw new IllegalArgumentException();}
            catch(IllegalArgumentException invalid){throw new StorageFailure(400,"INVALID_FILE","文件摘要无效。");}
            total=Math.addExact(total,length.longValue());normalized.addObject().put("name",name).put("mime",mime).put("size",length.longValue()).put("sha256",sha);
        }
        if(total>MAX_OPERATION_BYTES)throw new StorageFailure(413,"FILE_TOO_LARGE","单次图片保存批次超过 64 MiB，请分别保存。");return new Plan(normalized,total);
    }
    ObjectNode reserve(long id,String operation,JsonNode metadata,int preferred)throws Exception {
        FileStorage.operationId(operation);Plan plan=plan(metadata);if(preferred!=1&&preferred!=2)throw StorageFailure.unavailable();
        return database.run(operation,c->{
            String generation=user(c,id);FileStorage.Operation prior=existing(c,id,generation,operation,plan.hash());
            if(prior!=null){
                if("COMMITTED".equals(prior.state))return prior.result();
                terminal(prior);reserveCapacity(c,operation,plan.bytes);database.markReservation(operation);return pending(prior);
            }
            ProviderOperations.transferLibraryQuota(c,operation,id);
            try(PreparedStatement q=c.prepareStatement("SELECT used_bytes,reserved_bytes FROM file_storage_users WHERE user_id=? FOR UPDATE")){
                q.setLong(1,id);try(ResultSet r=q.executeQuery()){if(!r.next()||!FileStorage.fits(r.getLong(1),r.getLong(2),plan.bytes))throw new StorageFailure(413,"QUOTA_EXCEEDED","账户文件库已达到 2 GB 上限（含回收箱及待完成上传）。");}
            }
            ArrayNode fixed=plan.fingerprint.deepCopy();for(JsonNode file:fixed)((ObjectNode)file).put("id",UUID.randomUUID().toString());
            FileStorage.Operation op=record(c,id,generation,operation,plan.hash(),"UPLOAD",preferred,plan.bytes,fixed,"RESERVED");
            try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_users SET reserved_bytes=reserved_bytes+? WHERE user_id=? AND generation=?")){
                q.setLong(1,plan.bytes);q.setLong(2,id);q.setString(3,generation);if(q.executeUpdate()!=1)throw new SQLException("Quota owner changed");
            }
            ReplicaAuthority.Lease lease=database.currentLease();
            try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_upload_reservations(op_id,owner_node_id,owner_mysql_uuid,owner_epoch,owner_permit,reserved_view) VALUES(?,?,?,?,?,?)")){
                q.setString(1,operation);q.setInt(2,Integer.parseInt(database.config.getProperty("server.id")));q.setString(3,lease.mysqlUuid());q.setString(4,lease.epoch());q.setString(5,lease.stamp());q.setString(6,lease.view());q.executeUpdate();
            }
            reserveCapacity(c,operation,plan.bytes);database.markReservation(operation);return pending(op);
        });
    }
    private void actor(HttpServletRequest request,Connection c,long id,String generation)throws Exception {
        if(!generation.equals(user(c,id)))throw StorageFailure.unavailable();
        var session=request.getSession(false);if(session!=null&&(!Objects.equals(session.getAttribute("starhub.account.id"),id)||!AccountServlet.activeSession(session)))throw new StorageFailure(401,"AUTHENTICATION_REQUIRED","登录状态已更改，请重新登录。");
    }
    private FileStorage.Operation uploading(Connection c,long id,String op,byte[] hash)throws Exception {
        String generation=user(c,id);FileStorage.Operation prior=existing(c,id,generation,op,hash);if(prior==null)throw new StorageFailure(409,"RESERVATION_REQUIRED","请先为此上传预留空间。");
        if(!"COMMITTED".equals(prior.state))terminal(prior);return prior;
    }
    private ChunkedPayloadStore.Plan filePlan(Connection c,String file)throws Exception {
        try(PreparedStatement q=c.prepareStatement("SELECT plan_json FROM starhub_file_payloads WHERE file_id=?")){q.setString(1,file);try(ResultSet r=q.executeQuery()){return r.next()?AtomicPayloads.parse(ModelGateway.JSON.readTree(r.getString(1))):null;}}
    }
    ObjectNode complete(HttpServletRequest request,long id,String op,List<FileStorage.Upload> files)throws Exception {
        Plan plan=plan(metadata(files));if(database.inside()){if(database.reservationChangedHere(op))throw new SQLException("Reservation must be separately confirmed");return publish(request,id,op,files,plan);}
        ObjectNode replay=stageContent(request,id,op,files);return replay!=null?replay:publish(request,id,op,files,plan);
    }
    ObjectNode stageContent(HttpServletRequest request,long id,String op,List<FileStorage.Upload> files)throws Exception {
        if(database.inside())throw new SQLException("Chunked stages require separately confirmed transactions");
        FileStorage.operationId(op);Plan plan=plan(metadata(files));byte[] hash=plan.hash();
        FileStorage.Operation original=database.readHint(c->{String generation;try(PreparedStatement q=c.prepareStatement("SELECT generation FROM users WHERE id=? AND active=1")){q.setLong(1,id);try(ResultSet r=q.executeQuery()){if(!r.next())throw StorageFailure.unavailable();generation=r.getString(1);}}FileStorage.Operation prior=existing(c,id,generation,op,hash);if(prior==null)throw new StorageFailure(409,"RESERVATION_REQUIRED","请先为此上传预留空间。");return prior;});
        if("COMMITTED".equals(original.state))return database.run(op,c->{var current=uploading(c,id,op,hash);actor(request,c,id,current.generation);return current.result();});
        ArrayNode fixed=(ArrayNode)ModelGateway.JSON.readTree(original.files);int index=0;
        for(var file:files){
            String fileId=fixed.get(index++).path("id").asText();ChunkedPayloadStore.Plan payload=payloads.plan(fileId,op,"FILE",id+":"+original.generation,Base64.getEncoder().encodeToString(hash)+":"+fileId,file.content);
            AtomicPayloads.Parent parent=c->{var current=uploading(c,id,op,hash);actor(request,c,id,current.generation);if(!"RESERVED".equals(current.state))throw StorageFailure.pending();contentCapacity(c,op,plan.bytes);};
            database.run(op,c->{parent.verify(c);ChunkedPayloadStore.Plan old=filePlan(c,fileId);if(old==null){try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_file_payloads(file_id,operation_id,plan_json) VALUES(?,?,?)")){q.setString(1,fileId);q.setString(2,op);q.setString(3,AtomicPayloads.json(payload).toString());q.executeUpdate();}}else if(!AtomicPayloads.json(old).equals(AtomicPayloads.json(payload)))throw new StorageFailure(409,"OPERATION_CONFLICT","上传内容与原预留不一致。");return null;});
            payloads.stage(payload,file.content,parent,c->refreshFileBudget(c,op,false));
        }
        return null;
    }
    private ObjectNode publish(HttpServletRequest request,long id,String op,List<FileStorage.Upload> files,Plan plan)throws Exception {
        return database.run(op,c->{
            FileStorage.Operation prior=uploading(c,id,op,plan.hash());actor(request,c,id,prior.generation);if("COMMITTED".equals(prior.state))return prior.result();contentCapacity(c,op,plan.bytes);
            ArrayNode fixed=(ArrayNode)ModelGateway.JSON.readTree(prior.files);int index=0;
            for(var file:files){String fixedId=fixed.get(index++).path("id").asText();ChunkedPayloadStore.Plan payload=filePlan(c,fixedId);if(payload==null)throw StorageFailure.pending();ChunkedPayloadStore.publish(c,payload);
                try(PreparedStatement q=c.prepareStatement("INSERT INTO library_files(id,user_id,name,mime,size_bytes,content,storage_node_id,owner_generation) VALUES(?,?,?,?,?,NULL,?,?)")){q.setString(1,fixedId);q.setLong(2,id);q.setString(3,file.name);q.setString(4,file.mime);q.setInt(5,file.content.length);q.setInt(6,prior.node);q.setString(7,prior.generation);q.executeUpdate();}
            }
            try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_users SET used_bytes=used_bytes+?,reserved_bytes=reserved_bytes-? WHERE user_id=? AND generation=? AND reserved_bytes>=?")){q.setLong(1,plan.bytes);q.setLong(2,plan.bytes);q.setLong(3,id);q.setString(4,prior.generation);q.setLong(5,plan.bytes);if(q.executeUpdate()!=1)throw new SQLException("Quota reservation changed");}
            releaseCapacity(c,op);try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_operations SET state='COMMITTED' WHERE op_id=? AND state='RESERVED'")){q.setString(1,op);if(q.executeUpdate()!=1)throw new SQLException("Reservation terminal state changed");}prior.state="COMMITTED";return prior.result();
        });
    }
    private Map<Integer,Long> held(Connection c,String op)throws SQLException {
        Map<Integer,Long> result=new TreeMap<>();try(PreparedStatement q=c.prepareStatement("SELECT node_id,reserved_bytes FROM starhub_reserved_capacity WHERE op_id=? ORDER BY node_id FOR UPDATE")){q.setString(1,op);try(ResultSet r=q.executeQuery()){while(r.next())result.put(r.getInt(1),r.getLong(2));}}return result;
    }
    private Map<Integer,Long> totals(Connection c)throws SQLException {
        Map<Integer,Long> result=new TreeMap<>();try(PreparedStatement q=c.prepareStatement("SELECT node_id,reserved_bytes FROM starhub_capacity_totals ORDER BY node_id FOR UPDATE");ResultSet r=q.executeQuery()){while(r.next())result.put(r.getInt(1),r.getLong(2));}if(!result.keySet().equals(Set.of(1,2)))throw new SQLException("Physical reservation totals missing");return result;
    }
    private long stagedBytes(Connection c,String op)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT COALESCE(SUM(c.byte_count),0) FROM starhub_payload_chunks c JOIN starhub_file_payloads f ON f.file_id=c.payload_id WHERE f.operation_id=? OR f.deleting_op=?")){q.setString(1,op);q.setString(2,op);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Staged payload accounting unavailable");return r.getLong(1);}}
    }
    private void reservationIdentity(Connection c,String operation)throws Exception {
        ReplicaAuthority.Lease lease=database.currentLease();try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_upload_reservations(op_id,owner_node_id,owner_mysql_uuid,owner_epoch,owner_permit,reserved_view) VALUES(?,?,?,?,?,?)")){q.setString(1,operation);q.setInt(2,Integer.parseInt(database.config.getProperty("server.id")));q.setString(3,lease.mysqlUuid());q.setString(4,lease.epoch());q.setString(5,lease.stamp());q.setString(6,lease.view());q.executeUpdate();}
    }
    private void reserveCapacity(Connection c,String op,long bytes)throws Exception {refreshFileBudget(c,op,true);}
    private void refreshFileBudget(Connection c,String op,boolean check)throws Exception {
        FileStorage.Operation operation=find(c,op);if(operation==null)throw new SQLException("Physical parent intent is absent");
        ProviderOperations.transferLibraryCapacity(c,op,operation.user);
        long staged=stagedBytes(c,op);if(staged<0||staged>operation.logical)throw new SQLException("Staged file bytes exceed the immutable plan");
        // Existing stage pages already affect actual filesystem free space. Only
        // future insertion OR a complete explicit cancellation is reserved.
        long future=Set.of("CANCELLING","DELETING").contains(operation.state)?staged:Math.max(operation.logical-staged,staged);
        Map<Integer,StorageCluster.Probe> probes=cluster.probes();Map<Integer,Long> old=held(c,op),total=totals(c),desired=new TreeMap<>();
        for(var probe:probes.values()){
            long budget=probe.budget(future),peak=Math.addExact(total.get(probe.node)-old.getOrDefault(probe.node,0L),Math.addExact(budget,Math.addExact(probe.budget(guestFutureBytes(c)),probe.budget(AtomicDatabase.MAX_COMPETING_TRANSACTION_BYTES))));
            if(check&&(!probe.protectedReserve||probe.available<StorageCluster.FLOOR||peak>probe.available-StorageCluster.FLOOR))throw new StorageFailure(507,"INSUFFICIENT_STORAGE","服务器存储空间不足；原预留及已写入分段保留，可继续原操作或取消。");desired.put(probe.node,budget);
        }
        releaseCapacity(c,op);for(var entry:desired.entrySet()){
            try(PreparedStatement q=c.prepareStatement("UPDATE starhub_capacity_totals SET reserved_bytes=reserved_bytes+? WHERE node_id=?")){q.setLong(1,entry.getValue());q.setInt(2,entry.getKey());if(q.executeUpdate()!=1)throw new SQLException("Physical reservation total missing");}
            try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_reserved_capacity(op_id,node_id,reserved_bytes) VALUES(?,?,?)")){q.setString(1,op);q.setInt(2,entry.getKey());q.setLong(3,entry.getValue());q.executeUpdate();}
        }
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_upload_reservations SET reserved_view=? WHERE op_id=?")){q.setString(1,database.currentLease().view());q.setString(2,op);if(q.executeUpdate()!=1)throw new SQLException("Reservation view missing");}
    }
    static long guestFutureBytes(Connection c)throws SQLException {
        long result;try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COALESCE(SUM(c.byte_count),0) FROM starhub_payload_chunks c JOIN starhub_guest_payload_refs g ON g.payload_id=c.payload_id WHERE g.cleanup_refs>0 OR g.staging_refs>0")){if(!r.next())throw new SQLException("Guest future capacity unavailable");result=r.getLong(1);}
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COALESCE(SUM(OCTET_LENGTH(value_cipher)),0) FROM starhub_guest_staging")){if(!r.next())throw new SQLException("Legacy guest capacity unavailable");result=Math.addExact(result,r.getLong(1));}
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COALESCE(SUM(OCTET_LENGTH(f.value_cipher)),0) FROM starhub_guest_fields f WHERE EXISTS(SELECT 1 FROM starhub_guest_cleanup x WHERE x.guest_id=f.guest_id AND x.legacy_tab=f.tab_id AND x.legacy_field=f.field_name AND x.legacy_kind='fields' AND x.state='CANCELLING')")){if(!r.next())throw new SQLException("Legacy guest cleanup capacity unavailable");result=Math.addExact(result,r.getLong(1));}return result;
    }
    void transientCapacity(Connection c,long bytes)throws Exception {
        Map<Integer,Long> totals=totals(c);for(var probe:cluster.probes().values()){
            long peak=Math.addExact(totals.get(probe.node),Math.addExact(probe.budget(guestFutureBytes(c)),Math.addExact(probe.budget(bytes),probe.budget(AtomicDatabase.MAX_COMPETING_TRANSACTION_BYTES))));
            if(probe.available<StorageCluster.FLOOR||peak>probe.available-StorageCluster.FLOOR)throw new StorageFailure(507,"INSUFFICIENT_STORAGE","服务器存储空间不足，请保留本地数据并稍后重试。");
        }
    }
    private void contentCapacity(Connection c,String op,long bytes)throws Exception {
        String view;try(PreparedStatement q=c.prepareStatement("SELECT reserved_view FROM starhub_upload_reservations WHERE op_id=? FOR UPDATE")){q.setString(1,op);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Reservation identity missing");view=r.getString(1);}}
        if(!database.currentLease().view().equals(view))throw new StorageFailure(503,"RESERVATION_VIEW_CHANGED","服务节点已更改，请使用原操作标识重新确认预留。");
        refreshFileBudget(c,op,true);
    }
    private void releaseCapacity(Connection c,String op)throws SQLException {
        for(var entry:held(c,op).entrySet())try(PreparedStatement q=c.prepareStatement("UPDATE starhub_capacity_totals SET reserved_bytes=reserved_bytes-? WHERE node_id=? AND reserved_bytes>=?")){
            q.setLong(1,entry.getValue());q.setInt(2,entry.getKey());q.setLong(3,entry.getValue());if(q.executeUpdate()!=1)throw new SQLException("Physical reservation release mismatch");
        }
        try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_reserved_capacity WHERE op_id=?")){q.setString(1,op);q.executeUpdate();}
    }
    ObjectNode cancel(long id,String operation)throws Exception {
        if(database.inside())throw new SQLException("Chunk cleanup requires separate confirmed transactions");FileStorage.operationId(operation);
        FileStorage.Operation prior=database.run(operation,c->{String generation=user(c,id);FileStorage.Operation op=find(c,operation);if(op==null||op.user!=id||!generation.equals(op.generation)||!"UPLOAD".equals(op.kind))throw new StorageFailure(404,"OPERATION_NOT_FOUND","上传操作不存在。");
            if("COMMITTED".equals(op.state)||"CANCELLED".equals(op.state))return op;
            if(!Set.of("RESERVED","CANCELLING").contains(op.state))throw StorageFailure.pending();
            try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_operations SET state='CANCELLING' WHERE op_id=? AND state IN ('RESERVED','CANCELLING')")){q.setString(1,operation);q.executeUpdate();}op.state="CANCELLING";refreshFileBudget(c,operation,true);return op;
        });
        if("COMMITTED".equals(prior.state)||"CANCELLED".equals(prior.state))return prior.result();
        AtomicPayloads.Parent parent=c->{String generation=user(c,id);var current=find(c,operation);if(current==null||current.user!=id||!generation.equals(current.generation)||!"CANCELLING".equals(current.state))throw StorageFailure.pending();try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM library_files l JOIN starhub_file_payloads p ON p.file_id=l.id WHERE p.operation_id=?")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong(1)!=0)throw new SQLException("Published bytes cannot be cancelled");}}refreshFileBudget(c,operation,true);};
        List<ChunkedPayloadStore.Plan> plans=database.run(operation,c->{parent.verify(c);List<ChunkedPayloadStore.Plan> values=new ArrayList<>();try(PreparedStatement q=c.prepareStatement("SELECT plan_json FROM starhub_file_payloads WHERE operation_id=?")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){while(r.next())values.add(AtomicPayloads.parse(ModelGateway.JSON.readTree(r.getString(1))));}}return values;});
        for(var plan:plans){payloads.owner(plan,id+":"+prior.generation,"FILE");if(!plan.operation().equals(operation))throw StorageFailure.unavailable();boolean exists=database.run(operation,c->{parent.verify(c);try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM starhub_payload_manifests WHERE payload_id=?")){q.setString(1,plan.id());try(ResultSet r=q.executeQuery()){r.next();return r.getLong(1)==1;}}});if(exists)payloads.cleanup(plan,parent,false,c->refreshFileBudget(c,operation,false));}
        return database.run(operation,c->{parent.verify(c);
            for(var plan:plans)try(PreparedStatement q=c.prepareStatement("SELECT state FROM starhub_payload_manifests WHERE payload_id=?")){q.setString(1,plan.id());try(ResultSet r=q.executeQuery()){if(r.next()&&!"CANCELLED".equals(r.getString(1)))throw StorageFailure.pending();}}
            try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_users SET reserved_bytes=reserved_bytes-? WHERE user_id=? AND generation=? AND reserved_bytes>=?")){q.setLong(1,prior.logical);q.setLong(2,id);q.setString(3,prior.generation);q.setLong(4,prior.logical);if(q.executeUpdate()!=1)throw new SQLException("Quota cancellation mismatch");}
            releaseCapacity(c,operation);try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_operations SET state='CANCELLED' WHERE op_id=? AND state='CANCELLING'")){q.setString(1,operation);if(q.executeUpdate()!=1)throw new SQLException("Upload cancellation state changed");}prior.state="CANCELLED";return prior.result();
        });
    }
    private static ObjectNode pending(FileStorage.Operation op)throws Exception{return op.result().put("pending",true).put("reservedBytes",op.logical);}
    private static void terminal(FileStorage.Operation op){
        if("CANCELLED".equals(op.state)||"CANCELLING".equals(op.state)||"ABORTED".equals(op.state))throw new StorageFailure(409,"OPERATION_ABORTED","此上传已取消，请重新提交新的上传。");
        if(!"RESERVED".equals(op.state))throw StorageFailure.pending();
    }
    ObjectNode trash(long id,String op,String file,boolean restore)throws Exception {
        FileStorage.operationId(op);String kind=restore?"RESTORE":"TRASH";byte[] hash=digest(kind,file);
        return database.run(op,c->{
            String generation=user(c,id);FileStorage.Operation prior=existing(c,id,generation,op,hash);if(prior!=null){if("COMMITTED".equals(prior.state))return prior.result();terminal(prior);throw StorageFailure.pending();}
            try(PreparedStatement q=c.prepareStatement("SELECT content IS NOT NULL FROM library_files WHERE id=? AND user_id=? AND owner_generation=? FOR UPDATE")){
                q.setString(1,file);q.setLong(2,id);q.setString(3,generation);try(ResultSet r=q.executeQuery()){if(!r.next())throw missing();if(r.getBoolean(1))throw legacyMutation();}
            }
            transientCapacity(c,AtomicHttp.MAX_MUTATION_RECEIPT_ROW_BYTES);
            try(PreparedStatement q=c.prepareStatement("SELECT deleting_op FROM starhub_file_payloads WHERE file_id=?")){q.setString(1,file);try(ResultSet r=q.executeQuery()){if(r.next()&&r.getString(1)!=null)throw StorageFailure.pending();}}
            var chunked=filePlan(c,file);if(chunked!=null&&!"PUBLISHED".equals(ChunkedPayloadStore.state(c,chunked)))throw StorageFailure.pending();
            try(PreparedStatement q=c.prepareStatement("UPDATE library_files SET deleted_at="+(restore?"NULL":"COALESCE(deleted_at,CURRENT_TIMESTAMP)")+" WHERE id=? AND user_id=? AND owner_generation=?")){
                q.setString(1,file);q.setLong(2,id);q.setString(3,generation);q.executeUpdate();
            }
            ArrayNode files=ModelGateway.JSON.createArrayNode();files.addObject().put("id",file);return record(c,id,generation,op,hash,kind,1,0,files).result();
        });
    }
    ObjectNode delete(long id,String op,String file)throws Exception {
        if(database.inside())throw new SQLException("Chunk cleanup requires separately confirmed transactions");FileStorage.operationId(op);byte[] hash=digest("DELETE",file);
        ChunkedPayloadStore.Plan payload=database.run(op,c->{String generation=user(c,id);var old=existing(c,id,generation,op,hash);if(old!=null&&"COMMITTED".equals(old.state))return null;return filePlan(c,file);});
        if(payload==null)return deleteLegacy(id,op,file);database.readHint(c->{String generation;try(PreparedStatement q=c.prepareStatement("SELECT generation FROM users WHERE id=? AND active=1")){q.setLong(1,id);try(ResultSet r=q.executeQuery()){if(!r.next())throw StorageFailure.unavailable();generation=r.getString(1);}}payloads.owner(payload,id+":"+generation,"FILE");if(!payload.id().equals(file))throw StorageFailure.unavailable();return null;});
        FileStorage.Operation deletion=database.run(op,c->{String generation=user(c,id);var old=existing(c,id,generation,op,hash);if(old!=null){if(!Set.of("COMMITTED","DELETING").contains(old.state))throw StorageFailure.pending();return old;}
            long size;try(PreparedStatement q=c.prepareStatement("SELECT size_bytes FROM library_files WHERE id=? AND user_id=? AND owner_generation=? AND deleted_at IS NOT NULL")){q.setString(1,file);q.setLong(2,id);q.setString(3,generation);try(ResultSet r=q.executeQuery()){if(!r.next())throw missing();size=r.getLong(1);}}
            try(PreparedStatement q=c.prepareStatement("UPDATE starhub_file_payloads SET deleting_op=? WHERE file_id=? AND deleting_op IS NULL")){q.setString(1,op);q.setString(2,file);if(q.executeUpdate()!=1)throw StorageFailure.pending();}
            ArrayNode ids=ModelGateway.JSON.createArrayNode();ids.addObject().put("id",file);var result=record(c,id,generation,op,hash,"DELETE",1,size,ids,"DELETING");reservationIdentity(c,op);refreshFileBudget(c,op,true);ChunkedPayloadStore.retire(c,payload);return result;
        });
        if("COMMITTED".equals(deletion.state))return deletion.result();
        AtomicPayloads.Parent parent=c->{String generation=user(c,id);var current=existing(c,id,generation,op,hash);if(current==null||!"DELETING".equals(current.state))throw StorageFailure.pending();try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM library_files l JOIN starhub_file_payloads p ON p.file_id=l.id WHERE l.id=? AND l.user_id=? AND l.owner_generation=? AND l.deleted_at IS NOT NULL AND p.deleting_op=?")){q.setString(1,file);q.setLong(2,id);q.setString(3,generation);q.setString(4,op);try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong(1)!=1)throw new SQLException("Deleted publication proof changed");}}refreshFileBudget(c,op,true);};
        payloads.cleanup(payload,parent,true,c->refreshFileBudget(c,op,false));
        return database.run(op,c->{parent.verify(c);if(!"CANCELLED".equals(ChunkedPayloadStore.state(c,payload)))throw StorageFailure.pending();
            try(PreparedStatement q=c.prepareStatement("DELETE FROM library_files WHERE id=? AND user_id=? AND owner_generation=? AND deleted_at IS NOT NULL")){q.setString(1,file);q.setLong(2,id);q.setString(3,deletion.generation);if(q.executeUpdate()!=1)throw new SQLException("File publication changed during cleanup");}
            try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_users SET used_bytes=used_bytes-? WHERE user_id=? AND generation=? AND used_bytes>=?")){q.setLong(1,deletion.logical);q.setLong(2,id);q.setString(3,deletion.generation);q.setLong(4,deletion.logical);if(q.executeUpdate()!=1)throw new SQLException("Quota cleanup mismatch");}
            try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_operations SET state='COMMITTED' WHERE op_id=? AND state='DELETING'")){q.setString(1,op);if(q.executeUpdate()!=1)throw new SQLException("Deletion terminal state changed");}releaseCapacity(c,op);deletion.state="COMMITTED";return deletion.result();
        });
    }
    private static StorageFailure legacyMutation(){return new StorageFailure(503,"LEGACY_CAPACITY_MIGRATION_REQUIRED","未分段的历史文件尚未通过独立删除/修改容量校准；原文件和配额保留，请先受控迁移。");}
    private ObjectNode deleteLegacy(long id,String op,String file)throws Exception {
        FileStorage.operationId(op);byte[] hash=digest("DELETE",file);
        return database.run(op,c->{
            String generation=user(c,id);FileStorage.Operation prior=existing(c,id,generation,op,hash);if(prior!=null){if("COMMITTED".equals(prior.state))return prior.result();terminal(prior);throw StorageFailure.pending();}
            try(PreparedStatement q=c.prepareStatement("SELECT id FROM library_files WHERE id=? AND user_id=? AND owner_generation=? AND deleted_at IS NOT NULL")){q.setString(1,file);q.setLong(2,id);q.setString(3,generation);try(ResultSet r=q.executeQuery()){if(!r.next())throw missing();}}
            throw legacyMutation();
        });
    }
    ObjectNode status(long id,String operation)throws Exception {
        FileStorage.operationId(operation);return database.run(operation,c->{
            String generation=user(c,id);FileStorage.Operation result=find(c,operation);
            if(result==null||result.user!=id||!generation.equals(result.generation))throw new StorageFailure(404,"OPERATION_NOT_FOUND","操作不存在。");return result.result();
        });
    }
    ObjectNode summary(long id,String operation)throws Exception {
        FileStorage.operationId(operation);return database.run(operation,c->{String generation=user(c,id);
            try(PreparedStatement q=c.prepareStatement("SELECT state,kind,logical_bytes FROM file_storage_operations WHERE op_id=? AND user_id=? AND user_generation=?")){
                q.setString(1,operation);q.setLong(2,id);q.setString(3,generation);try(ResultSet r=q.executeQuery()){
                    if(!r.next())throw new StorageFailure(404,"OPERATION_NOT_FOUND","操作不存在。");
                    return ModelGateway.JSON.createObjectNode().put("operationId",operation).put("state",r.getString(1)).put("kind",r.getString(2)).put("logicalBytes",r.getLong(3));
                }
            }
        });
    }
    private FileStorage.Operation existing(Connection c,long id,String generation,String operation,byte[] hash)throws Exception {
        FileStorage.Operation prior=find(c,operation);if(prior==null)return null;
        try(PreparedStatement q=c.prepareStatement("SELECT request_hash FROM file_storage_operations WHERE op_id=?")){
            q.setString(1,operation);try(ResultSet r=q.executeQuery()){
                if(!r.next()||prior.user!=id||!generation.equals(prior.generation)||!MessageDigest.isEqual(hash,r.getBytes(1)))
                    throw new StorageFailure(409,"OPERATION_CONFLICT","操作标识已用于其他请求或旧账户。");
            }
        }
        return prior;
    }
    private FileStorage.Operation record(Connection c,long user,String generation,String id,byte[] hash,String kind,int node,long size,ArrayNode files)throws SQLException {
        return record(c,user,generation,id,hash,kind,node,size,files,"COMMITTED");
    }
    private FileStorage.Operation record(Connection c,long user,String generation,String id,byte[] hash,String kind,int node,long size,ArrayNode files,String state)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("INSERT INTO file_storage_operations(op_id,user_id,user_generation,request_hash,kind,node_id,state,logical_bytes,physical_bytes,primary_physical_bytes,files_json) VALUES(?,?,?,?,?,?,?,?,0,0,?)")){
            q.setString(1,id);q.setLong(2,user);q.setString(3,generation);q.setBytes(4,hash);q.setString(5,kind);q.setInt(6,node);q.setString(7,state);q.setLong(8,size);q.setString(9,files.toString());q.executeUpdate();
        }
        FileStorage.Operation op=new FileStorage.Operation();op.id=id;op.user=user;op.generation=generation;op.kind=kind;op.node=node;op.state=state;op.logical=size;op.files=files.toString();return op;
    }
    private FileStorage.Operation find(Connection c,String operation)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT user_id,user_generation,kind,node_id,state,logical_bytes,files_json FROM file_storage_operations WHERE op_id=?")){
            q.setString(1,operation);try(ResultSet r=q.executeQuery()){
                if(!r.next())return null;FileStorage.Operation op=new FileStorage.Operation();op.id=operation;op.user=r.getLong(1);op.generation=r.getString(2);op.kind=r.getString(3);op.node=r.getInt(4);op.state=r.getString(5);op.logical=r.getLong(6);op.files=r.getString(7);return op;
            }
        }
    }
    private static StorageFailure missing(){return new StorageFailure(404,"FILE_NOT_FOUND","文件不存在或尚未移入回收箱。");}
}
