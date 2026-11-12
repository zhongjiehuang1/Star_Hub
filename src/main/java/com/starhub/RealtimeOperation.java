package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;

/** One live relay has one durable SENT ticket; a crashed relay can never reconnect that ID. */
final class RealtimeOperation implements RealtimeBridge.Operation {
    private final HttpSession session;private ProviderOperations.Ticket ticket;private String id;private boolean ended;
    RealtimeOperation(HttpSession session){this.session=session;}
    public void start(JsonNode input)throws Exception {
        id=FileStorage.operationId(input.path("operationId").asText());FileStorage service=FileStorage.get();service.ensureReady();if(service.atomic==null)throw StorageFailure.unavailable();
        ProviderOperations.Owner owner=ProviderIdentity.from(session,input,service.atomic);ProviderOperations operations=service.atomic.providers;
        var claimed=operations.claim(owner,id,"realtime:live-session",input);if(!claimed.state().equals("CLAIMED"))throw ProviderOperations.pending(id);ticket=operations.dispatch(owner,id,"realtime:live-session",input);
    }
    public AutoCloseable enter(){if(ticket==null)throw new IllegalStateException("Realtime dispatch not claimed");return ticket.enter();}
    public void verify()throws Exception {if(ticket!=null)ticket.beforeNetwork();}
    public String id(){return id;}
    public String durability(){return ticket==null?null:ticket.lease.mode();}
    public synchronized void end(boolean uncertain){
        if(ended||ticket==null)return;ended=true;
        if(uncertain){ticket.unknown();return;}
        try{ticket.complete(200,ModelGateway.JSON.createObjectNode().put("type","realtime").put("status","relay_ended").put("audioRetained",false).put("providerBillingMayContinue",true).put("text","实时中继已结束。音频不保留，也不会使用原操作编号重新连接；服务商可能仍在处理此前输入。"));}
        catch(Exception unknown){ticket.unknown();}
    }
}
