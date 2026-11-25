package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;

/** A server-side, quota-counted copy from this session's original guest receipt.
 * The durable library operation binds source IDs before bytes are published. */
final class ProviderImageImports {
    @FunctionalInterface interface Source {ProviderOperations.Owner owner()throws Exception;}
    @FunctionalInterface interface Upload {void save(long user,String operation,List<FileStorage.Upload> files)throws Exception;}
    record Result(ObjectNode body,String durability,String destinationName){}
    private final AtomicFileStorage files;private final ProviderOperations providers;private final Upload upload;private final AtomicPayloads proofs;
    ProviderImageImports(AtomicFileStorage files,ProviderOperations providers,Upload upload){this.files=files;this.providers=providers;this.upload=upload;proofs=new AtomicPayloads(files.database,files.database.config);}
    private String name(long user,String generation,String operation,String source,String media)throws Exception {
        byte[] proof=proofs.mac("guest-image-import-v1",ModelGateway.JSON.writeValueAsBytes(List.of(user,generation,operation,source,media)));
        return "Star-Hub-import-"+source+"-"+media+"-"+HexFormat.of().formatHex(proof);
    }
    private static boolean sameName(String expected,String actual){return java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private Result status(long user,String operation,String source,String media,ProviderOperations.Owner actor)throws Exception {
        return files.database.run(operation,c->{
            actor.verify().verify(c);String generation=FileStorage.lockUser(c,user);String expectedName=name(user,generation,operation,source,media);ObjectNode result=ModelGateway.JSON.createObjectNode().put("operationId",source).put("mediaId",media).put("importOperationId",operation).put("state","ABSENT");
            try(PreparedStatement q=c.prepareStatement("SELECT user_id,user_generation,state,kind,files_json FROM file_storage_operations WHERE op_id=?")){
                q.setString(1,operation);try(ResultSet r=q.executeQuery()){
                    if(r.next()){
                        if(r.getLong(1)!=user||!generation.equals(r.getString(2)))throw new StorageFailure(404,"IMPORT_OPERATION_NOT_FOUND","导入操作不存在。");
                        JsonNode plan=ModelGateway.JSON.readTree(r.getString(5));
                        if(!r.getString(4).equals("UPLOAD")||!plan.isArray()||plan.size()!=1||!sameName(expectedName,plan.get(0).path("name").asText()))throw new StorageFailure(409,"IMPORT_OPERATION_CONFLICT","导入编号已绑定其他来源，请先查询原操作。");
                        JsonNode item=plan.get(0);FileStorage.operationId(item.path("id").asText());
                        if(!ProviderOperations.IMAGE_TYPES.contains(item.path("mime").asText())||item.path("size").asLong()<1||item.path("size").asLong()>GeneratedImages.MAX_IMAGE)throw new SQLException("Import file plan is invalid");
                        String state=r.getString(3);if(!Set.of("RESERVED","COMMITTED","CANCELLING","CANCELLED").contains(state))throw new SQLException("Import state is invalid");result.put("state",state);
                        if(state.equals("COMMITTED"))result.put("fileId",item.path("id").asText()).put("mime",item.path("mime").asText()).put("size",item.path("size").asLong());
                    }
                }
            }
            String mode=files.database.currentLease().mode();result.put("durability",mode);return new Result(result,mode,expectedName);
        });
    }
    record File(String id,String mime,String durability){}
    File file(long user,String operation,String source,String file,ProviderOperations.Owner actor)throws Exception {
        FileStorage.operationId(operation);FileStorage.operationId(source);FileStorage.operationId(file);
        if(!actor.identity().startsWith("account:"+user+":"))throw new StorageFailure(401,"ACCOUNT_CHANGED","请登录原账户读取图片。");
        return files.database.run(operation,c->{
            actor.verify().verify(c);String generation=FileStorage.lockUser(c,user);
            try(PreparedStatement q=c.prepareStatement("SELECT user_id,user_generation,state,kind,files_json FROM file_storage_operations WHERE op_id=?")){
                q.setString(1,operation);try(ResultSet r=q.executeQuery()){
                    if(!r.next()||r.getLong(1)!=user||!generation.equals(r.getString(2)))throw missing();
                    if(!r.getString(3).equals("COMMITTED"))throw new StorageFailure(409,"IMPORT_OPERATION_PENDING","原图片导入尚未确认，请查询原导入编号。");
                    JsonNode plan=ModelGateway.JSON.readTree(r.getString(5));if(!r.getString(4).equals("UPLOAD")||!plan.isArray()||plan.size()!=1)throw missing();JsonNode item=plan.get(0);
                    String name=item.path("name").asText(),prefix="Star-Hub-import-"+source+"-";
                    if(!name.startsWith(prefix)||name.length()!=prefix.length()+36+65||!file.equals(item.path("id").asText())||!ProviderOperations.IMAGE_TYPES.contains(item.path("mime").asText()))throw missing();
                    String media=FileStorage.operationId(name.substring(prefix.length(),prefix.length()+36));if(!sameName(name(user,generation,operation,source,media),name))throw missing();return new File(file,item.path("mime").asText(),files.database.currentLease().mode());
                }
            }
        });
    }
    private static StorageFailure missing(){return new StorageFailure(404,"IMPORTED_IMAGE_NOT_FOUND","导入图片不存在。");}
    Result execute(long user,String operation,String source,String media,boolean statusOnly,ProviderOperations.Owner actor,Source guest)throws Exception {
        FileStorage.operationId(operation);FileStorage.operationId(source);FileStorage.operationId(media);
        if(!actor.identity().startsWith("account:"+user+":"))throw new StorageFailure(401,"ACCOUNT_CHANGED","请登录原账户完成导入。");
        Result original=status(user,operation,source,media,actor);String state=original.body.path("state").asText();
        // Completed retry deliberately precedes guest lookup/expiry. The same
        // account's confirmed original file is the durable import result.
        if(state.equals("COMMITTED")||statusOnly)return original;
        if(!Set.of("ABSENT","RESERVED").contains(state))throw new StorageFailure(409,"IMPORT_OPERATION_TERMINAL","原导入已取消，请先核对原操作。");
        ProviderOperations.Owner previous=guest.owner();if(!previous.identity().startsWith("guest:"))throw new StorageFailure(401,"GUEST_IMPORT_REQUIRED","请选择当前会话原访客图片。");
        var image=providers.media(previous,source,media);
        if(image.fileId()!=null||image.bytes()==null||image.bytes().length==0||image.bytes().length>GeneratedImages.MAX_IMAGE)throw new SQLException("Guest image has no bounded immutable content");
        upload.save(user,operation,List.of(new FileStorage.Upload(original.destinationName,image.mime(),image.bytes())));
        Result completed=status(user,operation,source,media,actor);if(!completed.body.path("state").asText().equals("COMMITTED"))throw StorageFailure.pending();return completed;
    }
}
