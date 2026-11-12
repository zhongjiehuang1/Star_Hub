package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import jakarta.servlet.http.*;

/** Encrypted, certified guest state; the replicated HttpSession contains only an identity string. */
public final class GuestSessionServlet extends HttpServlet {
    // Preserve existing UTF-16 code-unit bounds, including packed binary attachments.
    static final int MAX_FIELD=12_000_000,MAX_SESSION=24_000_000,MAX_TOTAL=64_000_000,MAX_TABS=64;
    static final String IDENTITY="starhub.guest.identity";
    private final AtomicGuestStorage supplied;
    public GuestSessionServlet(){supplied=null;}
    GuestSessionServlet(AtomicGuestStorage store){supplied=store;}
    private static void send(HttpServletResponse res,int status,JsonNode body)throws IOException {
        res.setStatus(status);res.setContentType("application/json;charset=UTF-8");res.setHeader("Cache-Control","no-store");ModelGateway.JSON.writeValue(res.getOutputStream(),body);
    }
    protected void doPost(HttpServletRequest req,HttpServletResponse res)throws IOException {
        if(!"web".equals(req.getHeader("X-Starhub-Client"))||!DeploymentConfig.allowsOrigin(req.getHeader("Origin"))||req.getContentType()==null||!req.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")){send(res,403,ModelGateway.JSON.createObjectNode());return;}
        HeavyRequestBody memory;
        try{memory=HeavyRequestBody.read(req,1_048_576,"/api/guest-session");}
        catch(StorageFailure rejected){if(rejected.status==429)res.setHeader("Retry-After","1");send(res,rejected.status,ModelGateway.JSON.createObjectNode().put("code",rejected.code).put("state","UNKNOWN").put("error",rejected.getMessage()));return;}
        try(memory){byte[] bytes=memory.bytes;
        JsonNode input;try{input=ModelGateway.JSON.readTree(bytes);}catch(Exception invalid){send(res,400,ModelGateway.JSON.createObjectNode());return;}
        if(input==null||!input.isObject()){send(res,400,ModelGateway.JSON.createObjectNode());return;}
        try {
            AtomicGuestStorage store=supplied;
            if(store==null){FileStorage service=FileStorage.get();service.ensureReady();if(service.atomic==null)throw StorageFailure.unavailable();store=service.atomic.guest;}
            HttpSession session=req.getSession(!"renew".equals(input.path("action").asText()));if(session==null)throw new StorageFailure(401,"GUEST_SESSION_REQUIRED","访客会话已失效，请重新打开会话。");String identity;
            synchronized(session){Object current=session.getAttribute(IDENTITY);if(current!=null&&!(current instanceof String))throw StorageFailure.unavailable();identity=(String)current;}
            AtomicGuestStorage.Reply reply=store.handle(session.getId(),identity,input,System.currentTimeMillis());
            // Never publish an identity before its database transaction/GTID has
            // been confirmed. A lost initial reply can recover via cookie HMAC.
            synchronized(session){session.setAttribute(IDENTITY,reply.identity());}
            res.setHeader("X-Four-Project-Durability",reply.durability());res.setHeader("X-Four-Project-Degraded",Boolean.toString(reply.durability().equals("single")));
            send(res,200,reply.body());
        }catch(StorageFailure failed){send(res,failed.status,ModelGateway.JSON.createObjectNode().put("code",failed.code).put("error",failed.getMessage()));}
        catch(Exception unknown){send(res,503,ModelGateway.JSON.createObjectNode().put("code","GUEST_OPERATION_PENDING").put("state","UNKNOWN").put("operationId",AtomicGuestStorage.operationId(input)).put("error","访客数据结果尚未确认，请保留当前页面并使用原操作重试。"));}
        }
    }
}
