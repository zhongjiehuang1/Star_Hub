package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Durable pre-dispatch claims. SENT/UNKNOWN are never permission to repeat an external effect. */
final class ProviderOperations {
    static final int RESULT_LIMIT=32*1024*1024,MAX_OPERATIONS=16384,MAX_ACTIVE_PER_OWNER=32;
    // This reader is used only after the immutable encrypted result's byte limit
    // and SHA have been checked. Public request parsing keeps its existing limits.
    private static final com.fasterxml.jackson.databind.ObjectMapper RESULTS=new com.fasterxml.jackson.databind.ObjectMapper(
        com.fasterxml.jackson.core.JsonFactory.builder().streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
            .maxStringLength(RESULT_LIMIT).maxDocumentLength(RESULT_LIMIT).build()).build());
    static final int MAX_IMAGES=4;
    static final Set<String> IMAGE_TYPES=Set.of("image/png","image/jpeg","image/webp");
    record Media(byte[] bytes,String mime,String fileId,String durability){}
    @FunctionalInterface interface VerifyOwner {void verify(Connection c)throws Exception;}
    record Owner(String identity,VerifyOwner verify){}
    record Receipt(String operationId,String state,int httpStatus,ObjectNode result,String durability){
        Receipt {if(result!=null)result.put("durability",durability).put("replicationMode",durability).put("degraded","single".equals(durability));}
    }
    private static final ThreadLocal<Ticket> CURRENT=new ThreadLocal<>();
    private final AtomicDatabase database;private final StorageCluster cluster;private final byte[] vault,secret;
    private final AtomicPayloads payloads;
    ProviderOperations(AtomicDatabase database,StorageCluster cluster,Properties config){
        this.database=database;this.cluster=cluster;vault=Base64.getDecoder().decode(config.getProperty("vault.key",""));secret=config.getProperty("storage.idempotency.secret","").getBytes(StandardCharsets.UTF_8);
        if(!Set.of(16,24,32).contains(vault.length)||secret.length<32)throw new IllegalArgumentException("Provider receipt encryption/identity is not configured");
        payloads=new AtomicPayloads(database,config);
    }
    private byte[] hmac(String purpose,String value)throws Exception {Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(secret,"HmacSHA256"));return hmac.doFinal(ModelGateway.JSON.writeValueAsBytes(List.of(purpose,value)));}
    private byte[] owner(Owner owner)throws Exception{return hmac("provider-owner",owner.identity);}
    private byte[] request(Owner owner,String kind,JsonNode input)throws Exception {ObjectNode normalized=((ObjectNode)input).deepCopy();normalized.remove("operationId");return hmac("provider-request",owner.identity+"\n"+kind+"\n"+AtomicHttp.canonical(normalized));}
    static long imageBudget(String kind,JsonNode input){
        if(!kind.endsWith(":image"))return 0;JsonNode count=input.path("parameters").path("n");
        int images=count.isMissingNode()?1:count.isIntegralNumber()&&count.canConvertToInt()?count.intValue():0;
        if(images<1||images>MAX_IMAGES)throw new StorageFailure(400,"INVALID_IMAGE_COUNT","每次图片生成数量必须为 1 至 4。");return (long)images*GeneratedImages.MAX_IMAGE;
    }
    Receipt claim(Owner owner,String operation,String kind,JsonNode input)throws Exception {
        ProviderTransportPolicy.require();
        FileStorage.operationId(operation);if(kind.isBlank()||kind.length()>120||!input.isObject())throw new StorageFailure(400,"INVALID_PROVIDER_OPERATION","模型操作请求无效。");byte[] ownerHash=owner(owner),requestHash=request(owner,kind,input);
        return database.run(operation,c->{
            owner.verify.verify(c);Receipt existing=bound(c,owner,ownerHash,operation,kind,requestHash);if(existing!=null)return existing;
            try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM starhub_provider_operations WHERE owner_hash=? AND state IN ('CLAIMED','SENT','UNKNOWN')")){q.setBytes(1,ownerHash);try(ResultSet r=q.executeQuery()){r.next();if(r.getInt(1)>=MAX_ACTIVE_PER_OWNER)throw new StorageFailure(429,"PROVIDER_OPERATION_LIMIT","尚有未确认的模型操作，请先查询原结果。");}}
            try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM starhub_provider_operations")){r.next();if(r.getLong(1)>=MAX_OPERATIONS)throw new StorageFailure(429,"PROVIDER_OPERATION_LIMIT","模型操作记录容量已满，请稍后重试。");}
            long imageBudget=imageBudget(kind,input),logical=0;Long accountId=null;String generation=null;
            if(imageBudget>0&&owner.identity.startsWith("account:")){
                String[] fields=owner.identity.split(":",4);if(fields.length!=4)throw new SQLException("Invalid provider account identity");accountId=Long.valueOf(fields[1]);generation=FileStorage.lockUser(c,accountId);
                if(!generation.equals(fields[2]))throw new StorageFailure(401,"ACCOUNT_CHANGED","账户身份已更改。");
                try(PreparedStatement q=c.prepareStatement("SELECT used_bytes,reserved_bytes FROM file_storage_users WHERE user_id=? FOR UPDATE")){q.setLong(1,accountId);try(ResultSet r=q.executeQuery()){if(!r.next()||!FileStorage.fits(r.getLong(1),r.getLong(2),imageBudget))throw new StorageFailure(413,"QUOTA_EXCEEDED","图片请求尚未发送：文件库需预留每张最多 12 MiB，且总配额为 2 GB（含回收箱及待完成操作）。");}}
                try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_users SET reserved_bytes=reserved_bytes+? WHERE user_id=? AND generation=?")){q.setLong(1,imageBudget);q.setLong(2,accountId);q.setString(3,generation);if(q.executeUpdate()!=1)throw new SQLException("Provider quota owner changed");}logical=imageBudget;
            }
            long now=System.currentTimeMillis();try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_provider_operations(operation_id,owner_hash,request_hash,kind,state,reserved_view,created_millis,updated_millis,account_user_id,account_generation,image_budget_bytes,logical_reserved) VALUES(?,?,?,?,'CLAIMED',?,?,?,?,?,?,?)")){q.setString(1,operation);q.setBytes(2,ownerHash);q.setBytes(3,requestHash);q.setString(4,kind);q.setString(5,database.currentLease().view());q.setLong(6,now);q.setLong(7,now);if(accountId==null)q.setNull(8,Types.BIGINT);else q.setLong(8,accountId);q.setString(9,generation);q.setLong(10,imageBudget);q.setLong(11,logical);q.executeUpdate();}
            reserve(c,operation);return new Receipt(operation,"CLAIMED",0,null,database.currentLease().mode());
        });
    }
    Ticket dispatch(Owner owner,String operation,String kind,JsonNode input)throws Exception {
        byte[] ownerHash=owner(owner),requestHash=request(owner,kind,input);String nonce=UUID.randomUUID().toString();byte[] dispatchHash=hmac("provider-dispatch",nonce);
        return database.run(operation,c->{
            owner.verify.verify(c);Receipt existing=bound(c,owner,ownerHash,operation,kind,requestHash);if(existing==null||!existing.state.equals("CLAIMED"))throw pending(operation);
            reserve(c,operation);try(PreparedStatement q=c.prepareStatement("UPDATE starhub_provider_operations SET state='SENT',dispatch_hash=?,updated_millis=? WHERE operation_id=? AND state='CLAIMED'")){q.setBytes(1,dispatchHash);q.setLong(2,System.currentTimeMillis());q.setString(3,operation);if(q.executeUpdate()!=1)throw pending(operation);}
            return new Ticket(owner,operation,dispatchHash,database.currentLease());
        });
    }
    Receipt status(Owner owner,String operation)throws Exception {
        FileStorage.operationId(operation);byte[] expected=owner(owner);
        return database.run(operation,c->{owner.verify.verify(c);try(PreparedStatement q=c.prepareStatement("SELECT owner_hash,state,response_status,result_cipher,dispatch_hash FROM starhub_provider_operations WHERE operation_id=?")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){
            if(!r.next())return new Receipt(operation,"ABSENT",0,null,database.currentLease().mode());if(!MessageDigest.isEqual(expected,r.getBytes(1)))throw new StorageFailure(404,"PROVIDER_OPERATION_NOT_FOUND","模型操作不存在。");return receipt(c,owner,operation,r.getString(2),r.getInt(3),r.getBytes(4),r.getBytes(5));
        }}});
    }
    private Receipt bound(Connection c,Owner principal,byte[] owner,String operation,String kind,byte[] request)throws Exception {
        try(PreparedStatement q=c.prepareStatement("SELECT owner_hash,request_hash,kind,state,response_status,result_cipher,dispatch_hash FROM starhub_provider_operations WHERE operation_id=?")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){
            if(!r.next())return null;if(!MessageDigest.isEqual(owner,r.getBytes(1))||!MessageDigest.isEqual(request,r.getBytes(2))||!kind.equals(r.getString(3)))throw new StorageFailure(409,"PROVIDER_OPERATION_CONFLICT","模型操作编号已绑定其他请求；请先核对原结果。");return receipt(c,principal,operation,r.getString(4),r.getInt(5),r.getBytes(6),r.getBytes(7));
        }}
    }
    private record ImagePlan(ChunkedPayloadStore.Plan plan,String binding,String mime,byte[] plainHash){}
    private record Intent(byte[] dispatch,int status,String terminal,ChunkedPayloadStore.Plan plan,String binding,List<ImagePlan> images,String account){}
    private static ObjectNode intentJson(Intent value){
        ObjectNode json=AtomicPayloads.json(value.plan);var images=json.putArray("images");
        for(var image:value.images)images.addObject().set("plan",AtomicPayloads.json(image.plan));
        for(int index=0;index<value.images.size();index++){var image=value.images.get(index);((ObjectNode)images.get(index)).put("binding",image.binding).put("mime",image.mime).put("plainSha256",Base64.getEncoder().encodeToString(image.plainHash));}
        if(value.account!=null)json.put("account",value.account);return json;
    }
    private Intent intent(Connection c,String operation)throws Exception {
        try(PreparedStatement q=c.prepareStatement("SELECT dispatch_hash,response_status,terminal_state,plan_json,cipher_binding FROM starhub_provider_payloads WHERE operation_id=?")){
            q.setString(1,operation);try(ResultSet r=q.executeQuery()){
                if(!r.next())return null;JsonNode json=ModelGateway.JSON.readTree(r.getString(4));List<ImagePlan> images=new ArrayList<>();
                for(var image:json.path("images"))images.add(new ImagePlan(AtomicPayloads.parse(image.path("plan")),image.path("binding").asText(),image.path("mime").asText(),Base64.getDecoder().decode(image.path("plainSha256").asText())));
                return new Intent(r.getBytes(1),r.getInt(2),r.getString(3),AtomicPayloads.parse(json),r.getString(5),List.copyOf(images),json.has("account")?json.path("account").asText():null);
            }
        }
    }
    private String accountReader(Owner principal)throws Exception {
        if(!principal.identity.startsWith("account:"))return null;String[] fields=principal.identity.split(":",4);
        if(fields.length<3)return null;return Base64.getEncoder().encodeToString(hmac("provider-account-media",fields[1]));
    }
    private static String imageBinding(String operation,byte[] dispatch,int index,String mime){return "provider-image:"+operation+":"+HexFormat.of().formatHex(dispatch)+":"+index+":"+mime;}
    private static String binding(String operation,byte[] dispatch){return "provider-result:"+operation+":"+HexFormat.of().formatHex(dispatch);}
    private void verifyIntent(Owner principal,String operation,byte[] dispatch,Intent value)throws Exception {
        verifyIntentStructure(operation,dispatch,value);
        if(!MessageDigest.isEqual(value.plan.ownerHash(),payloads.mac("payload-owner-v1",principal.identity))||!Objects.equals(value.account,accountReader(principal)))throw new SQLException("Provider payload owner changed");
    }
    private void verifyIntentStructure(String operation,byte[] dispatch,Intent value)throws Exception {
        if(dispatch==null||dispatch.length!=32||value.dispatch==null||!MessageDigest.isEqual(value.dispatch,dispatch))throw new SQLException("Provider result dispatch binding changed");
        String expected=binding(operation,dispatch);ChunkedPayloadStore.Plan plan=value.plan;
        if(!value.binding.equals(expected)||!plan.id().equals(AtomicPayloads.id("provider-result-v1",operation))||!plan.operation().equals(operation)||!plan.kind().equals("PROVIDER_RESULT")
                ||!MessageDigest.isEqual(plan.requestHash(),payloads.mac("payload-request-v1",expected))
                ||plan.byteCount()<28||plan.byteCount()>RESULT_LIMIT+28L||value.status<100||value.status>599||value.images.size()>MAX_IMAGES
                ||!Set.of("COMPLETED","COMPLETED_NO_RESULT").contains(value.terminal))throw new SQLException("Provider result intent is inconsistent");
        Set<String> ids=new HashSet<>();
        for(var image:value.images){var child=image.plan;
            // Child IDs preserve their source index even when account-saved images precede them.
            int index=-1;for(int candidate=0;candidate<MAX_IMAGES;candidate++)if(child.id().equals(AtomicPayloads.id("provider-image-v1",operation+":"+candidate)))index=candidate;
            if(index<0||!ids.add(child.id())||!IMAGE_TYPES.contains(image.mime)||!image.binding.equals(imageBinding(operation,dispatch,index,image.mime))||image.plainHash.length!=32
                    ||!child.operation().equals(operation)||!child.kind().equals("PROVIDER_IMAGE")||!MessageDigest.isEqual(child.ownerHash(),plan.ownerHash())
                    ||!MessageDigest.isEqual(child.requestHash(),payloads.mac("payload-request-v1",image.binding))||child.byteCount()<=28||child.byteCount()>GeneratedImages.MAX_IMAGE+28L)throw new SQLException("Provider image intent is inconsistent");
        }
    }
    private Receipt receipt(Connection c,Owner principal,String operation,String state,int status,byte[] encrypted,byte[] dispatch)throws Exception {
        Intent value=intent(c,operation);
        if(value==null){
            // Historical inline ciphertext is read only. Rewriting/deleting a
            // large old BLOB under FULL row image is not a bounded transaction.
            ObjectNode result=encrypted==null?null:(ObjectNode)RESULTS.readTree(decrypt(encrypted,operation));
            return new Receipt(operation,state,status,result,database.currentLease().mode());
        }
        verifyIntent(principal,operation,dispatch,value);
        if(encrypted!=null)throw new SQLException("Provider result has conflicting storage formats");
        if(Set.of("SENT","UNKNOWN").contains(state))return finalizeReady(c,principal,operation,state,value);
        if(!state.equals(value.terminal)||status!=value.status||!ChunkedPayloadStore.state(c,value.plan).equals("PUBLISHED"))throw new SQLException("Provider terminal result was not atomically published");
        for(var image:value.images)if(!ChunkedPayloadStore.state(c,image.plan).equals("PUBLISHED"))throw new SQLException("Provider image was not atomically published");
        return retained(c,operation,value);
    }
    private boolean ready(Connection c,ChunkedPayloadStore.Plan plan)throws Exception {
        String state=ChunkedPayloadStore.state(c,plan);if(state.equals("STAGING")){
            long count;try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM starhub_payload_chunks WHERE payload_id=?")){q.setString(1,plan.id());try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Provider image count unavailable");count=r.getLong(1);}}
            if(count<plan.chunkCount())return false;if(count!=plan.chunkCount())throw new SQLException("Provider image has unexpected chunks");ChunkedPayloadStore.seal(c,plan);return true;
        }if(!Set.of("READY","PUBLISHED").contains(state))throw new SQLException("Provider image is not recoverable");return true;
    }
    private Receipt retained(Connection c,String operation,Intent value)throws Exception {
        byte[] cipher=ChunkedPayloadStore.readBytes(c,value.plan,RESULT_LIMIT+28);
        JsonNode decoded=RESULTS.readTree(payloads.decrypt(value.plan.id(),value.binding,cipher));
        if(!(decoded instanceof ObjectNode result)||!operation.equals(result.path("operationId").asText())||!value.terminal.equals(result.path("operationState").asText()))throw new SQLException("Provider result content binding changed");
        return new Receipt(operation,value.terminal,value.status,result,database.currentLease().mode());
    }
    private Receipt finalizeReady(Connection c,Owner principal,String operation,String originalState,Intent value)throws Exception {
        for(var image:value.images)if(!ready(c,image.plan))return new Receipt(operation,originalState,0,null,database.currentLease().mode());
        String state=ChunkedPayloadStore.state(c,value.plan);
        if(state.equals("STAGING")){
            long count;try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM starhub_payload_chunks WHERE payload_id=?")){q.setString(1,value.plan.id());try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Provider payload count unavailable");count=r.getLong(1);}}
            if(count<value.plan.chunkCount())return new Receipt(operation,originalState,0,null,database.currentLease().mode());
            if(count!=value.plan.chunkCount())throw new SQLException("Provider payload has unexpected chunks");
            // Complete bytes after a crash before seal are safe to recover only
            // after every index, length, chunk hash and full SHA are verified.
            ChunkedPayloadStore.seal(c,value.plan);state="READY";
        }
        if(!Set.of("READY","PUBLISHED").contains(state))throw new SQLException("Provider payload is not recoverable");
        if(held(c,operation).isEmpty())throw new SQLException("Unpublished provider result lost its capacity reservation");
        Receipt result=retained(c,operation,value);principal.verify.verify(c);
        if(!libraryReady(c,operation,value,result.result))return new Receipt(operation,originalState,0,null,database.currentLease().mode());
        for(var image:value.images)ChunkedPayloadStore.publish(c,image.plan);
        ChunkedPayloadStore.publish(c,value.plan);
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_provider_operations SET state=?,response_status=?,result_cipher=NULL,updated_millis=? WHERE operation_id=? AND state IN ('SENT','UNKNOWN') AND dispatch_hash=?")){
            q.setString(1,value.terminal);q.setInt(2,value.status);q.setLong(3,System.currentTimeMillis());q.setString(4,operation);q.setBytes(5,value.dispatch);if(q.executeUpdate()!=1)throw pending(operation);
        }
        release(c,operation);releaseLogical(c,operation);return result;
    }
    private boolean libraryReady(Connection c,String operation,Intent value,ObjectNode result)throws Exception {
        Set<String> files=new HashSet<>();for(var item:result.path("media"))if(item.path("type").asText().equals("image")&&item.hasNonNull("fileId")){
            if(!files.add(FileStorage.operationId(item.path("fileId").asText())))throw new SQLException("Duplicate provider library reference");
        }
        if(files.isEmpty())return true;if(value.account==null)throw new SQLException("Guest provider result contains account file references");
        try(PreparedStatement q=c.prepareStatement("SELECT user_id,state,files_json FROM file_storage_operations WHERE op_id=? AND kind='UPLOAD'")){
            q.setString(1,operation);try(ResultSet r=q.executeQuery()){
                if(!r.next())throw new SQLException("Provider library intent is absent");
                if(!value.account.equals(Base64.getEncoder().encodeToString(hmac("provider-account-media",Long.toString(r.getLong(1))))))throw new SQLException("Provider library account differs");
                Set<String> expected=new HashSet<>();for(var file:ModelGateway.JSON.readTree(r.getString(3)))expected.add(file.path("id").asText());
                if(!files.equals(expected))throw new SQLException("Provider library references differ from the durable upload plan");
                return r.getString(2).equals("COMMITTED");
            }
        }
    }
    /** Transfers the same image allocation from provider future budget into the
     * durable library reservation in the same guarded SQL transaction. */
    static void transferLibraryQuota(Connection c,String operation,long user)throws Exception {
        Ticket ticket=CURRENT.get();if(ticket==null||!ticket.operation.equals(operation))return;
        if(!ticket.owner.identity.startsWith("account:"+user+":"))throw new SQLException("Provider/library actor differs");
        ticket.releaseLibraryQuota(c,user);
    }
    static void transferLibraryCapacity(Connection c,String operation,long user)throws Exception {
        Ticket ticket=CURRENT.get();if(ticket==null||!ticket.operation.equals(operation))return;
        if(!ticket.owner.identity.startsWith("account:"+user+":"))throw new SQLException("Provider/library actor differs");
        ticket.refreshLibraryCapacity(c);
    }
    final class Ticket {
        final Owner owner;final String operation;final byte[] dispatchHash;final ReplicaAuthority.Lease lease;private final AtomicBoolean finished=new AtomicBoolean();private final AtomicBoolean attempted=new AtomicBoolean();
        Ticket(Owner owner,String operation,byte[] dispatchHash,ReplicaAuthority.Lease lease){this.owner=owner;this.operation=operation;this.dispatchHash=dispatchHash;this.lease=lease;}
        void beforeNetwork()throws IOException {
            if(finished.get())throw new IOException("Provider operation has ended");try{database.unchangedPermit(lease);}catch(SQLException unavailable){throw new IOException("Provider dispatch authority changed; query the original operation",unavailable);}
            attempted.set(true);
        }
        void releaseLibraryQuota(Connection c,long user)throws Exception {
            verifyTicket(c,this);owner.verify.verify(c);
            try(PreparedStatement q=c.prepareStatement("SELECT account_user_id,image_budget_bytes,logical_reserved FROM starhub_provider_operations WHERE operation_id=? FOR UPDATE")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong(1)!=user||r.getLong(2)<=0||r.getLong(3)!=r.getLong(2))throw new SQLException("Provider image quota transfer changed");}}
            releaseLogical(c,operation);
        }
        void refreshLibraryCapacity(Connection c)throws Exception {verifyTicket(c,this);reserve(c,operation,false);}
        void prepareAccountImages(ObjectNode result)throws Exception {
            if(!owner.identity.startsWith("account:"))throw new SQLException("An account is required for a library result recipe");
            try{complete(200,result);}catch(StorageFailure pending){if(pending.status!=409||!pending.code.equals("PROVIDER_OPERATION_UNKNOWN"))throw pending;}
        }
        boolean attempted(){return attempted.get();}
        boolean active(){return !finished.get();}
        AutoCloseable enter(){if(CURRENT.get()!=null)throw new IllegalStateException("Overlapping provider dispatch context");CURRENT.set(this);return ()->{if(CURRENT.get()==this)CURRENT.remove();};}
        Receipt complete(int status,ObjectNode result)throws Exception {
            if(finished.get())throw pending(operation);String state="COMPLETED";ObjectNode stored=result.deepCopy();boolean account=owner.identity.startsWith("account:"),cannotRetain=false;
            List<ImagePlan> imagePlans=new ArrayList<>();Map<String,byte[]> sourceImages=new HashMap<>();int imageIndex=0;
            for(JsonNode item:stored.path("media"))if(item.isObject()&&item.path("type").asText().equals("image")){
                if(imageIndex>=MAX_IMAGES)throw new IOException("Too many generated images");ObjectNode media=(ObjectNode)item;String mime=media.path("mime").asText();
                byte[] raw=null;JsonNode binary=media.get("imageBytes");if(binary!=null&&binary.isBinary())raw=binary.binaryValue();
                if(raw==null&&media.path("url").asText().startsWith("data:image/")){String url=media.path("url").asText();int comma=url.indexOf(',');if(comma>0&&url.substring(0,comma).endsWith(";base64")){mime=url.substring(5,comma-7);if(url.length()-comma-1>((GeneratedImages.MAX_IMAGE+2L)/3)*4)throw new IOException("Generated image too large");raw=Base64.getDecoder().decode(url.substring(comma+1));}}
                if(account&&media.path("fileId").asText().matches("[0-9a-f-]{36}")&&!stored.path("imageSaveFailed").asBoolean(false)){media.remove(List.of("url","imageBytes"));if(!IMAGE_TYPES.contains(mime))mime="image/png";media.put("mime",mime);}
                else if(!account&&raw!=null&&raw.length>0&&raw.length<=GeneratedImages.MAX_IMAGE&&IMAGE_TYPES.contains(mime)){
                    String childId=AtomicPayloads.id("provider-image-v1",operation+":"+imageIndex),childBinding=imageBinding(operation,dispatchHash,imageIndex,mime);byte[] cipher=payloads.encrypt(childId,childBinding,raw);
                    var child=payloads.plan(childId,operation,"PROVIDER_IMAGE",owner.identity,childBinding,cipher);imagePlans.add(new ImagePlan(child,childBinding,mime,FileStorage.hash(raw)));sourceImages.put(childId,raw);
                    media.remove(List.of("url","imageBytes","fileId"));media.put("mime",mime);media.set("mediaRef",ModelGateway.JSON.createObjectNode().put("operationId",operation).put("mediaId",childId).put("mimeType",mime));
                }else cannotRetain=true;
                imageIndex++;
            }
            stored.put("operationId",operation).put("operationState",state);byte[] bytes=cannotRetain?null:bounded(stored);
            if(bytes==null){imagePlans.clear();sourceImages.clear();state="COMPLETED_NO_RESULT";stored=metadata(result).put("operationId",operation).put("operationState",state).put("error","模型操作已完成，但结果未保留或超过上限；原操作不会再次调用服务商。").put("code","PROVIDER_RESULT_NOT_RETAINED");bytes=ModelGateway.JSON.writeValueAsBytes(stored);}
            if(status<100||status>599)throw new IllegalArgumentException("Invalid provider response status");
            final String terminalState=state,cipherBinding=binding(operation,dispatchHash);
            String payloadId=AtomicPayloads.id("provider-result-v1",operation);byte[] encrypted=payloads.encrypt(payloadId,cipherBinding,bytes);
            ChunkedPayloadStore.Plan plan=payloads.plan(payloadId,operation,"PROVIDER_RESULT",owner.identity,cipherBinding,encrypted);
            Intent prepared=new Intent(dispatchHash,status,terminalState,plan,cipherBinding,List.copyOf(imagePlans),accountReader(owner));
            Receipt already=database.run(operation,c->{
                owner.verify.verify(c);Intent old=intent(c,operation);
                if(old!=null){verifyIntent(owner,operation,dispatchHash,old);requireSameIntent(prepared,old);}
                try(PreparedStatement q=c.prepareStatement("SELECT state,response_status,result_cipher,dispatch_hash FROM starhub_provider_operations WHERE operation_id=?")){
                    q.setString(1,operation);try(ResultSet r=q.executeQuery()){
                        if(!r.next())throw pending(operation);String current=r.getString(1);
                        if(Set.of("COMPLETED","COMPLETED_NO_RESULT").contains(current))return receipt(c,owner,operation,current,r.getInt(2),r.getBytes(3),r.getBytes(4));
                    }
                }
                verifyTicket(c,this);
                long imageBytes=0;for(var image:prepared.images)imageBytes=Math.addExact(imageBytes,image.plan.byteCount()-28);
                try(PreparedStatement q=c.prepareStatement("SELECT image_budget_bytes FROM starhub_provider_operations WHERE operation_id=?")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){if(!r.next()||imageBytes>r.getLong(1))throw new SQLException("Provider images exceed the pre-dispatch budget");}}
                if(old==null)try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_provider_payloads(operation_id,dispatch_hash,response_status,terminal_state,plan_json,cipher_binding) VALUES(?,?,?,?,?,?)")){
                    q.setString(1,operation);q.setBytes(2,dispatchHash);q.setInt(3,status);q.setString(4,terminalState);q.setString(5,intentJson(prepared).toString());q.setString(6,cipherBinding);q.executeUpdate();
                }
                for(var image:prepared.images)ChunkedPayloadStore.reserve(c,image.plan);
                ChunkedPayloadStore.reserve(c,plan);reserve(c,operation);return null;
            });
            if(already!=null){finished.set(true);return already;}
            for(var image:prepared.images){byte[] raw=sourceImages.get(image.plan.id()),cipher=payloads.encrypt(image.plan.id(),image.binding,raw);
                payloads.stage(image.plan,cipher,c->{owner.verify.verify(c);verifyTicket(c,this);Intent current=intent(c,operation);if(current==null)throw pending(operation);verifyIntent(owner,operation,dispatchHash,current);requireSameIntent(prepared,current);reserve(c,operation);},c->reserve(c,operation,false));
            }
            payloads.stage(plan,encrypted,c->{owner.verify.verify(c);verifyTicket(c,this);Intent current=intent(c,operation);if(current==null)throw pending(operation);verifyIntent(owner,operation,dispatchHash,current);requireSameIntent(prepared,current);reserve(c,operation);},c->reserve(c,operation,false));
            Receipt completed=database.run(operation,c->{
                owner.verify.verify(c);Intent current=intent(c,operation);if(current==null)throw pending(operation);verifyIntent(owner,operation,dispatchHash,current);requireSameIntent(prepared,current);
                try(PreparedStatement q=c.prepareStatement("SELECT state,response_status,result_cipher,dispatch_hash FROM starhub_provider_operations WHERE operation_id=?")){
                    q.setString(1,operation);try(ResultSet r=q.executeQuery()){if(!r.next())throw pending(operation);return receipt(c,owner,operation,r.getString(1),r.getInt(2),r.getBytes(3),r.getBytes(4));}
                }
            });
            if(!Set.of("COMPLETED","COMPLETED_NO_RESULT").contains(completed.state))throw pending(operation);
            finished.set(true);return completed;
        }
        void unknown(){
            if(!finished.compareAndSet(false,true))return;
            try{database.run(operation,c->{verifyTicket(c,this);try(PreparedStatement q=c.prepareStatement("UPDATE starhub_provider_operations SET state='UNKNOWN',updated_millis=? WHERE operation_id=? AND state='SENT' AND dispatch_hash=?")){q.setLong(1,System.currentTimeMillis());q.setString(2,operation);q.setBytes(3,dispatchHash);q.executeUpdate();}return null;});}catch(Exception unavailable){/* SENT is already durable; never infer rollback or release its budget. */}
        }
    }
    static Ticket current(){return CURRENT.get();}
    static void beforeExternal()throws IOException {ProviderTransportPolicy.require();Ticket ticket=CURRENT.get();if(ticket==null)throw new IOException("A durable provider dispatch claim is required");ticket.beforeNetwork();}
    static StorageFailure pending(String operation){return new StorageFailure(409,"PROVIDER_OPERATION_UNKNOWN","模型操作可能已发送或正在执行。请查询原操作，不要自动重复调用；新操作可能再次计费。");}
    private void verifyTicket(Connection c,Ticket ticket)throws Exception {
        try(PreparedStatement q=c.prepareStatement("SELECT state,dispatch_hash FROM starhub_provider_operations WHERE operation_id=?")){q.setString(1,ticket.operation);try(ResultSet r=q.executeQuery()){if(!r.next()||!Set.of("SENT","UNKNOWN").contains(r.getString(1))||!MessageDigest.isEqual(ticket.dispatchHash,r.getBytes(2)))throw pending(ticket.operation);}}
    }
    private Map<Integer,Long> held(Connection c,String operation)throws SQLException {
        Map<Integer,Long> result=new TreeMap<>();try(PreparedStatement q=c.prepareStatement("SELECT node_id,reserved_bytes FROM starhub_provider_capacity WHERE operation_id=? ORDER BY node_id FOR UPDATE")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){while(r.next())result.put(r.getInt(1),r.getLong(2));}}return result;
    }
    private Map<Integer,Long> totals(Connection c)throws SQLException {
        Map<Integer,Long> result=new TreeMap<>();try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT node_id,reserved_bytes FROM starhub_capacity_totals ORDER BY node_id FOR UPDATE")){while(r.next())result.put(r.getInt(1),r.getLong(2));}if(!result.keySet().equals(Set.of(1,2)))throw new SQLException("Physical reservation totals are unavailable");return result;
    }
    private void reserve(Connection c,String operation)throws Exception {reserve(c,operation,true);}
    private void reserve(Connection c,String operation,boolean check)throws Exception {
        Intent current=intent(c,operation);long future=RESULT_LIMIT+28L;
        if(current==null){
            try(PreparedStatement q=c.prepareStatement("SELECT image_budget_bytes,account_user_id,logical_reserved FROM starhub_provider_operations WHERE operation_id=?")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){
                if(!r.next())throw new SQLException("Provider operation absent");long images=r.getLong(1),account=r.getLong(2),logical=r.getLong(3);boolean library=false;
                if(account>0&&images>0&&logical==0)try(PreparedStatement file=c.prepareStatement("SELECT state FROM file_storage_operations WHERE op_id=? AND user_id=? AND kind='UPLOAD'")){file.setString(1,operation);file.setLong(2,account);try(ResultSet upload=file.executeQuery()){library=upload.next()&&Set.of("RESERVED","COMMITTED").contains(upload.getString(1));}}
                if(!library&&images>0)future=Math.addExact(future,Math.addExact(images,(images/GeneratedImages.MAX_IMAGE)*28L));
            }}
        }
        else {future=remaining(c,current.plan);for(var image:current.images)future=Math.addExact(future,remaining(c,image.plan));}
        Map<Integer,Long> total=totals(c),old=held(c,operation),desired=new TreeMap<>();
        for(var p:cluster.probes().values()){
            long cost=p.budget(future),peak=Math.addExact(total.get(p.node)-old.getOrDefault(p.node,0L),Math.addExact(cost,Math.addExact(p.budget(AtomicFileStorage.guestFutureBytes(c)),p.budget(AtomicDatabase.MAX_COMPETING_TRANSACTION_BYTES))));
            if(check&&(!p.protectedReserve||p.available<StorageCluster.FLOOR||peak>p.available-StorageCluster.FLOOR))throw new StorageFailure(507,"INSUFFICIENT_STORAGE","模型结果无法安全预留存储空间；请求尚未重新发送。");desired.put(p.node,cost);
        }
        release(c,operation);for(var entry:desired.entrySet()){
            try(PreparedStatement q=c.prepareStatement("UPDATE starhub_capacity_totals SET reserved_bytes=reserved_bytes+? WHERE node_id=?")){q.setLong(1,entry.getValue());q.setInt(2,entry.getKey());if(q.executeUpdate()!=1)throw new SQLException("Physical reservation row changed");}
            try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_provider_capacity(operation_id,node_id,reserved_bytes) VALUES(?,?,?)")){q.setString(1,operation);q.setInt(2,entry.getKey());q.setLong(3,entry.getValue());q.executeUpdate();}
        }
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_provider_operations SET reserved_view=? WHERE operation_id=?")){q.setString(1,database.currentLease().view());q.setString(2,operation);if(q.executeUpdate()!=1)throw new SQLException("Provider reservation disappeared");}
    }
    private long remaining(Connection c,ChunkedPayloadStore.Plan plan)throws SQLException {
        long staged;try(PreparedStatement q=c.prepareStatement("SELECT COALESCE(SUM(byte_count),0) FROM starhub_payload_chunks WHERE payload_id=?")){q.setString(1,plan.id());try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Provider stage accounting unavailable");staged=r.getLong(1);}}
        if(staged<0||staged>plan.byteCount())throw new SQLException("Provider stage exceeds its immutable plan");return plan.byteCount()-staged;
    }
    Media media(Owner principal,String operation,String mediaId)throws Exception {
        FileStorage.operationId(operation);FileStorage.operationId(mediaId);
        return database.run(operation,c->{
            principal.verify.verify(c);byte[] dispatch;String state;byte[] ownerHash;
            try(PreparedStatement q=c.prepareStatement("SELECT owner_hash,state,dispatch_hash FROM starhub_provider_operations WHERE operation_id=?")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){if(!r.next())throw new StorageFailure(404,"PROVIDER_MEDIA_NOT_FOUND","图片不存在。");ownerHash=r.getBytes(1);state=r.getString(2);dispatch=r.getBytes(3);}}
            Intent value=intent(c,operation);if(value==null)throw new StorageFailure(404,"PROVIDER_MEDIA_NOT_FOUND","图片不存在。");
            boolean same=MessageDigest.isEqual(ownerHash,owner(principal)),account=value.account!=null&&value.account.equals(accountReader(principal));
            if(!same&&!account)throw new StorageFailure(404,"PROVIDER_MEDIA_NOT_FOUND","图片不存在。");
            verifyIntentStructure(operation,dispatch,value);if(!state.equals("COMPLETED"))throw pending(operation);
            if(!ChunkedPayloadStore.state(c,value.plan).equals("PUBLISHED"))throw new SQLException("Provider media parent not published");
            ObjectNode result=retained(c,operation,value).result;JsonNode found=null;
            for(var item:result.path("media"))if(item.path("type").asText().equals("image")&&(mediaId.equals(item.path("fileId").asText())||mediaId.equals(item.path("mediaRef").path("mediaId").asText()))){if(found!=null)throw new SQLException("Ambiguous provider media");found=item;}
            if(found==null)throw new StorageFailure(404,"PROVIDER_MEDIA_NOT_FOUND","图片不存在。");String mime=found.path("mime").asText();if(!IMAGE_TYPES.contains(mime))throw new SQLException("Invalid provider image type");
            if(mediaId.equals(found.path("fileId").asText())){if(!account)throw new StorageFailure(404,"PROVIDER_MEDIA_NOT_FOUND","图片不存在。");return new Media(null,mime,mediaId,database.currentLease().mode());}
            // Guest bytes require the exact original tab owner. The account
            // exception above is solely for a quota-counted current library file.
            if(!same)throw new StorageFailure(404,"PROVIDER_MEDIA_NOT_FOUND","图片不存在。");
            for(var image:value.images)if(mediaId.equals(image.plan.id())){
                if(!image.mime.equals(mime)||!ChunkedPayloadStore.state(c,image.plan).equals("PUBLISHED"))throw new SQLException("Provider image not published");
                byte[] cipher=ChunkedPayloadStore.readBytes(c,image.plan,GeneratedImages.MAX_IMAGE+28),plain=payloads.decrypt(image.plan.id(),image.binding,cipher);
                if(plain.length<1||plain.length>GeneratedImages.MAX_IMAGE||!MessageDigest.isEqual(FileStorage.hash(plain),image.plainHash))throw new SQLException("Provider image content changed");
                return new Media(plain,mime,null,database.currentLease().mode());
            }throw new SQLException("Provider image reference has no immutable child");
        });
    }
    private void releaseLogical(Connection c,String operation)throws SQLException {
        long user,bytes;String generation;
        try(PreparedStatement q=c.prepareStatement("SELECT account_user_id,account_generation,logical_reserved FROM starhub_provider_operations WHERE operation_id=? FOR UPDATE")){q.setString(1,operation);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Provider quota operation absent");user=r.getLong(1);generation=r.getString(2);bytes=r.getLong(3);}}
        if(bytes==0)return;if(bytes<0||user<=0||generation==null)throw new SQLException("Provider quota reservation corrupt");
        try(PreparedStatement q=c.prepareStatement("UPDATE file_storage_users SET reserved_bytes=reserved_bytes-? WHERE user_id=? AND generation=? AND reserved_bytes>=?")){q.setLong(1,bytes);q.setLong(2,user);q.setString(3,generation);q.setLong(4,bytes);if(q.executeUpdate()!=1)throw new SQLException("Provider quota release changed");}
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_provider_operations SET logical_reserved=0 WHERE operation_id=? AND logical_reserved=?")){q.setString(1,operation);q.setLong(2,bytes);if(q.executeUpdate()!=1)throw new SQLException("Provider quota transfer changed");}
    }
    private void release(Connection c,String operation)throws SQLException {
        for(var entry:held(c,operation).entrySet())try(PreparedStatement q=c.prepareStatement("UPDATE starhub_capacity_totals SET reserved_bytes=reserved_bytes-? WHERE node_id=? AND reserved_bytes>=?")){q.setLong(1,entry.getValue());q.setInt(2,entry.getKey());q.setLong(3,entry.getValue());if(q.executeUpdate()!=1)throw new SQLException("Physical provider reservation mismatch");}
        try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_provider_capacity WHERE operation_id=?")){q.setString(1,operation);q.executeUpdate();}
    }
    private static ObjectNode metadata(ObjectNode result){ObjectNode meta=ModelGateway.JSON.createObjectNode();for(String field:List.of("storageOperationId","imageSaveFailed","storageErrorCode","jobId","status","warning"))if(result.has(field)){
        JsonNode value=result.path(field);if(value.isBoolean()||value.isNumber())meta.set(field,value);else if(value.isTextual())meta.put(field,value.asText().substring(0,Math.min(512,value.asText().length())));
    }return meta;}
    private static final class Limit extends IOException{}
    private static byte[] bounded(JsonNode value)throws IOException {
        ByteArrayOutputStream data=new ByteArrayOutputStream();try{ModelGateway.JSON.writeValue(new OutputStream(){public void write(int b)throws IOException{if(data.size()>=RESULT_LIMIT)throw new Limit();data.write(b);}public void write(byte[] bytes,int offset,int count)throws IOException{if(count>RESULT_LIMIT-data.size())throw new Limit();data.write(bytes,offset,count);}},value);return data.toByteArray();}
        catch(IOException failed){for(Throwable cause=failed;cause!=null;cause=cause.getCause())if(cause instanceof Limit)return null;throw failed;}
    }
    private static void requireSameIntent(Intent expected,Intent actual)throws SQLException {
        if(expected.status!=actual.status||!expected.terminal.equals(actual.terminal)||!expected.binding.equals(actual.binding)
                ||!MessageDigest.isEqual(expected.dispatch,actual.dispatch)||!intentJson(expected).equals(intentJson(actual)))throw new SQLException("Provider result is already bound to a different completion");
    }
    private byte[] decrypt(byte[] cipher,String operation)throws Exception {if(cipher.length<28)throw new IOException("Invalid provider result ciphertext");return AccountServlet.crypt(false,Arrays.copyOfRange(cipher,12,cipher.length),vault,Arrays.copyOf(cipher,12),"starhub-provider-v1:"+operation);}
}
