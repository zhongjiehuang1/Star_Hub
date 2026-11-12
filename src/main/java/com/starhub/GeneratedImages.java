package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** Saves generated images directly to the same account-scoped library as uploads. */
final class GeneratedImages {
    static final int MAX_IMAGE=12*1024*1024;
    private static final Map<String,String> TYPES=Map.of("image/png","png","image/jpeg","jpg","image/webp","webp");
    private static final String FAILED="图片已生成，但未能保存到文件库；请在刷新前下载图片。";
    private static final String QUOTA="图片已生成，但账户文件库容量不足；请在刷新前下载图片。";
    static boolean fitsQuota(long used,long added){return used>=0&&added>=0&&added<=WorkspaceServlet.QUOTA&&used<=WorkspaceServlet.QUOTA-added;}
    private static final class Image {
        final ObjectNode media; final String mime; final byte[] bytes;
        Image(ObjectNode media,String mime,byte[] bytes){this.media=media;this.mime=mime;this.bytes=bytes;}
    }
    private static List<Image> images(JsonNode media){
        List<Image> found=new ArrayList<>();
        for(JsonNode entry:media){
            if(!entry.isObject()||!"image".equals(entry.path("type").asText()))continue;
            String mime=entry.path("mime").asText();byte[] data;
            if(entry.path("imageBytes").isBinary())try{data=entry.path("imageBytes").binaryValue();}catch(java.io.IOException invalid){throw new IllegalArgumentException();}
            else {
                String url=entry.path("url").asText();int comma=url.indexOf(',');
                if(comma<0||!url.startsWith("data:")||!url.substring(0,comma).endsWith(";base64"))throw new IllegalArgumentException();
                mime=url.substring(5,comma-7);String encoded=url.substring(comma+1);if(encoded.length()>((MAX_IMAGE+2)/3)*4)throw new IllegalArgumentException();data=Base64.getDecoder().decode(encoded);
            }
            if(!TYPES.containsKey(mime)||data.length==0||data.length>MAX_IMAGE)throw new IllegalArgumentException();
            found.add(new Image((ObjectNode)entry,mime,data));
        }
        return found;
    }
    static void save(HttpServletRequest req,JsonNode request,ObjectNode result){
        String expected=request.path("expectedEmail").asText();if(expected.isBlank())return;
        try {
            HttpSession session=req.getSession(false);
            if(!AccountServlet.activeSession(session)||!Objects.equals(expected,session.getAttribute("starhub.account.email")))throw new IllegalStateException();
            long user=(Long)session.getAttribute("starhub.account.id");List<Image> list=images(result.path("media"));
            if(list.isEmpty())throw new IllegalArgumentException();
            String operation=request.path("storageOperationId").asText();
            if(operation.isBlank()){
                if(FileStorage.get().replicated)throw new StorageFailure(400,"INVALID_OPERATION","图片保存需要稳定的操作标识，请刷新页面后重试。");
                operation=UUID.randomUUID().toString();
            }
            result.put("storageOperationId",operation);
            List<FileStorage.Upload> uploads=new ArrayList<>();int index=0;
            for(Image i:list)uploads.add(new FileStorage.Upload("Star-Hub-"+operation+"-"+(index++)+"."+TYPES.get(i.mime),i.mime,i.bytes));
            FileStorage service=FileStorage.get();ObjectNode saved;
            if(service.atomic!=null){
                var ticket=ProviderOperations.current();if(ticket==null||!operation.equals(ticket.operation))throw new StorageFailure(400,"INVALID_OPERATION","图片保存必须使用原模型操作编号。");
                var metadata=ModelGateway.JSON.createArrayNode();for(var upload:uploads){var item=upload.metadata();item.remove("id");metadata.add(item);}
                ObjectNode reserved=service.reserveUpload(req,user,operation,metadata);
                for(int i=0;i<list.size();i++)list.get(i).media.put("fileId",reserved.path("ids").get(i).asText()).put("mime",list.get(i).mime);
                // A complete small receipt recipe precedes the first file chunk.
                // Status can recover after file COMMITTED but before the handler's reply.
                ticket.prepareAccountImages(result);
                saved=service.uploadReserved(req,user,operation,uploads);
            }else saved=service.upload(req,user,operation,uploads);
            for(int i=0;i<list.size();i++)list.get(i).media.put("fileId",saved.path("ids").get(i).asText()).put("mime",list.get(i).mime);
        } catch(StorageFailure e){if(e.status>=500||e.code.equals("OPERATION_PENDING"))throw e;result.put("warning","QUOTA_EXCEEDED".equals(e.code)?QUOTA:e.getMessage());result.put("imageSaveFailed",true);result.put("storageErrorCode",e.code);}
        catch(Exception e){if(ProviderOperations.current()!=null)throw StorageFailure.pending();result.put("warning",FAILED);result.put("imageSaveFailed",true);}
    }
}
