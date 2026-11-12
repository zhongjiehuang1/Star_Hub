package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** A request cannot choose another account or recover a guest identity from a client UUID. */
final class ProviderIdentity {
    private ProviderIdentity(){}
    static ProviderOperations.Owner from(HttpSession session,JsonNode input,AtomicFileStorage files)throws Exception {
        if(session==null)throw new StorageFailure(401,"PROVIDER_SESSION_REQUIRED","请启用必要会话并重新打开页面。");
        String tab=input.path("ownerTab").asText(),expectedEmail=input.path("expectedEmail").asText();if(!tab.matches("[0-9a-f]{32}"))throw new StorageFailure(400,"INVALID_PROVIDER_OPERATION","模型操作需要当前标签页身份。");
        Object account=session.getAttribute("starhub.account.id");
        if(account instanceof Long id){
            String email=(String)session.getAttribute("starhub.account.email"),generation=(String)session.getAttribute("starhub.account.generation");Object raw=session.getAttribute("starhub.account.hash");
            if(!Objects.equals(expectedEmail,email)||generation==null||!(raw instanceof byte[]))throw new StorageFailure(401,"ACCOUNT_CHANGED","账户已更改，请先恢复原账户再查询操作。");byte[] hash=((byte[])raw).clone();
            return new ProviderOperations.Owner("account:"+id+":"+generation+":"+tab,c->{
                try{if(!Objects.equals(session.getAttribute("starhub.account.id"),id)||!Objects.equals(session.getAttribute("starhub.account.generation"),generation)||!Objects.equals(session.getAttribute("starhub.account.email"),email))throw changed();}catch(IllegalStateException expired){throw changed();}
                try(PreparedStatement q=c.prepareStatement("SELECT password_hash,email,generation FROM users WHERE id=? AND active=1")){q.setLong(1,id);try(ResultSet r=q.executeQuery()){if(!r.next()||!MessageDigest.isEqual(hash,r.getBytes(1))||!email.equals(r.getString(2))||!generation.equals(r.getString(3)))throw changed();}}
            });
        }
        if(!expectedEmail.isBlank())throw changed();
        String identity=(String)session.getAttribute(GuestSessionServlet.IDENTITY);String operation=FileStorage.operationId(input.path("operationId").asText());
        var request=ModelGateway.JSON.createObjectNode().put("action","identity").put("tab",tab).put("tx",operation.replace("-",""));AtomicGuestStorage.Reply resolved;
        try{resolved=files.guest.handle(session.getId(),identity,request,System.currentTimeMillis());}
        catch(StorageFailure expired){if(!expired.code.equals("GUEST_SESSION_EXPIRED"))throw expired;var renew=request.deepCopy().put("action","renew");files.guest.handle(session.getId(),identity,renew,System.currentTimeMillis());resolved=files.guest.handle(session.getId(),identity,request,System.currentTimeMillis());}
        identity=resolved.identity();session.setAttribute(GuestSessionServlet.IDENTITY,identity);final String guest=identity;
        return new ProviderOperations.Owner("guest:"+guest+":"+tab,c->{
            try{if(session.getAttribute("starhub.account.id")!=null||!guest.equals(session.getAttribute(GuestSessionServlet.IDENTITY)))throw changed();}catch(IllegalStateException expired){throw changed();}
            try(PreparedStatement q=c.prepareStatement("SELECT expires_at_millis FROM starhub_guest_sessions WHERE guest_id=?")){q.setString(1,guest);try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong(1)<=System.currentTimeMillis())throw changed();}}
        });
    }
    /** Read-only import of this browser session's previous guest gallery.
     * The client cannot supply or renew a guest identity, nor dispatch as it. */
    static ProviderOperations.Owner guestImport(HttpSession session,JsonNode input,AtomicFileStorage files)throws Exception {
        if(session==null||!(session.getAttribute("starhub.account.id") instanceof Long))throw changed();
        ProviderOperations.Owner account=from(session,input,files);String guest=(String)session.getAttribute(GuestSessionServlet.IDENTITY),tab=input.path("ownerTab").asText();
        if(guest==null)throw new StorageFailure(404,"GUEST_IMPORT_NOT_FOUND","当前会话没有可导入的访客图片。");FileStorage.operationId(guest);
        return new ProviderOperations.Owner("guest:"+guest+":"+tab,c->{
            account.verify().verify(c);
            try{if(!guest.equals(session.getAttribute(GuestSessionServlet.IDENTITY)))throw changed();}catch(IllegalStateException expired){throw changed();}
            try(PreparedStatement q=c.prepareStatement("SELECT expires_at_millis FROM starhub_guest_sessions WHERE guest_id=?")){q.setString(1,guest);try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong(1)<=System.currentTimeMillis())throw new StorageFailure(401,"GUEST_IMPORT_EXPIRED","原访客图片会话已过期；导入尚未完成，请保留原记录。");}}
        });
    }
    private static StorageFailure changed(){return new StorageFailure(401,"ACCOUNT_CHANGED","会话身份已更改，请恢复原会话后查询操作；不要重新发送收费请求。");}
}
