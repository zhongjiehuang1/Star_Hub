package com.starhub;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

/** Static pages and guest AI requests remain independent; account state always fails closed. */
public final class ClusterFilter implements Filter {
    public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException{
        HttpServletResponse res=(HttpServletResponse)response;
        FileStorage service;
        try{service=FileStorage.get();service.ensureReady();}
        catch(Exception unavailable){
            res.setStatus(503);res.setContentType("application/json;charset=UTF-8");res.setHeader("Cache-Control","no-store");
            ModelGateway.JSON.writeValue(res.getOutputStream(),ModelGateway.JSON.createObjectNode()
                    .put("code","CLUSTER_UNAVAILABLE").put("error","服务器故障，请等待恢复"));return;
        }
        try{
            if(service.replicated){
                if(service.atomic!=null){AtomicHttp.run((HttpServletRequest)request,res,chain,service);return;}
                ReplicationResponseGuard guarded=new ReplicationResponseGuard(res,service::ensureReady);
                chain.doFilter(request,guarded);guarded.confirmIfSuccessful();
            }else chain.doFilter(request,response);
        }
        catch(StorageFailure unavailable){
            if(res.isCommitted())throw unavailable;
            res.reset();
            res.setStatus(503);res.setContentType("application/json;charset=UTF-8");res.setHeader("Cache-Control","no-store");
            ModelGateway.JSON.writeValue(res.getOutputStream(),ModelGateway.JSON.createObjectNode()
                    .put("code","CLUSTER_UNAVAILABLE").put("error","服务器故障，请等待恢复"));
        }
    }
}
