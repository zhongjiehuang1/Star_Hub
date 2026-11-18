package com.starhub;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.concurrent.Semaphore;
import jakarta.servlet.http.*;

public final class ApiServlet extends HttpServlet {
    private final ModelGateway gateway = new ModelGateway();
    private final ChatStream streams;
    private final SearchGateway search = new SearchGateway();
    private static final Semaphore MEDIA_READS=new Semaphore(1);
    private final MediaGateway media = new MediaGateway();
    private final BatchGateway batches = new BatchGateway();
    public ApiServlet(){this(new ChatStream());}
    ApiServlet(ChatStream streams){this.streams=streams;}

    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        switch (String.valueOf(req.getPathInfo())) {
            case "/internal/recovery-ready":
                res.setHeader("Cache-Control","no-store");
                try{
                    String token=AccountServlet.config().getProperty("storage.probe.token"),authorization=req.getHeader("Authorization");
                    if(token==null||authorization==null||!java.security.MessageDigest.isEqual(("Bearer "+token).getBytes(java.nio.charset.StandardCharsets.UTF_8),authorization.getBytes(java.nio.charset.StandardCharsets.UTF_8))){res.setStatus(403);break;}
                    FileStorage service=FileStorage.active;res.setStatus(service!=null&&service.runtimeReady()?204:503);
                }catch(Exception unavailable){res.setStatus(503);}
                break;
            case "/cluster-health":
                res.setHeader("Cache-Control","no-store");
                try{FileStorage.get().ensureReady();send(res,200,ModelGateway.JSON.createObjectNode().put("status","ready"));}
                catch(Exception unavailable){send(res,503,ModelGateway.JSON.createObjectNode().put("status","unavailable"));}
                break;
            case "/server":
                res.setHeader("Cache-Control", "no-store");
                try { send(res,200,ModelGateway.JSON.createObjectNode().put("serverId",DeploymentConfig.serverId())); }
                catch(IllegalStateException missing){error(res,503,"服务器标识暂不可用。");}
                break;
            case "/health": send(res, 200, ModelGateway.JSON.createObjectNode().put("status", "ok").put("app", "Star Hub")); break;
            case "/catalog": send(res, 200, Catalog.publicData()); break;
            case "/maps/cities":
                res.setHeader("Cache-Control", "no-store");
                try { send(res, 200, CityLookup.find(req.getParameter("q"))); }
                catch (CityLookup.Busy busy) { res.setHeader("Retry-After", "1"); error(res, 429, "城市查询繁忙，请稍后重试。"); }
                catch (IOException | ExceptionInInitializerError | NoClassDefFoundError unavailable) { error(res, 503, "城市数据暂不可用。"); }
                break;
            case "/source":
                try { send(res,200,CallSources.read(req.getParameter("file"))); }
                catch(ModelGateway.Problem e){error(res,e.status,e.getMessage());}
                catch(IOException e){error(res,503,"本版本源码暂不可读取，请稍后重试。");}
                break;
            case "/providers": send(res, 200, ModelGateway.catalogue()); break;
            default: error(res, 404, "接口不存在。");
        }
    }
    protected void doPost(HttpServletRequest req,HttpServletResponse res)throws IOException {
        String path=req.getPathInfo();boolean mediaQuery="/provider-media".equals(path),query=mediaQuery||"/provider-operation".equals(path);
        if(!query&&!java.util.Set.of("/chat","/run","/batch","/check","/memory-extract").contains(String.valueOf(path))){error(res,404,"接口不存在。");return;}
        if(!"web".equals(req.getHeader("X-Starhub-Client"))||req.getContentType()==null||!req.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")){error(res,415,"请使用本站的 JSON 接口。");return;}
        if(!DeploymentConfig.allowsOrigin(req.getHeader("Origin"))){error(res,403,"请求来源不受支持。");return;}
        ProviderAdmission.Lease admission=ProviderAdmission.tryEnter();
        if(admission==null){res.setHeader("Cache-Control","no-store");res.setHeader("Retry-After","1");providerFailure(res,429,"PROVIDER_BUSY",null,"模型请求或结果读取繁忙，请保留原操作编号后查询或重试。");return;}
        ProviderOperations.Ticket ticket=null;String operation=null;
        try {
            int limit=query?16384:6*1024*1024;byte[] raw=req.getInputStream().readNBytes(limit+1);if(raw.length>limit){error(res,413,"请求过大，请保留原操作编号并检查输入。");return;}
            com.fasterxml.jackson.databind.JsonNode input=ModelGateway.JSON.readTree(raw);if(input==null||!input.isObject())throw new ModelGateway.Problem(400,"请求格式无效。");
            ((com.fasterxml.jackson.databind.node.ObjectNode)input).remove("_starhubFrozenMemory");
            operation=FileStorage.operationId(input.path("operationId").asText());FileStorage service=FileStorage.get();service.ensureReady();if(service.atomic==null)throw StorageFailure.unavailable();
            AccountServlet.providerIdle(service,req.getSession(false),!input.path("expectedEmail").asText().isBlank());
            if(input.has("guestImport")&&(!mediaQuery||!input.path("guestImport").isBoolean()))throw new StorageFailure(400,"INVALID_PROVIDER_OPERATION","访客导入仅适用于明确的只读图片请求。");
            ProviderOperations operations=service.atomic.providers;
            if(mediaQuery){media(req,res,input,service,operations,operation);return;}
            ProviderOperations.Owner owner=ProviderIdentity.from(req.getSession(true),input,service.atomic);
            if(query){var status=operations.status(owner,operation);requireResultRead(req,input,owner);providerStatus(res,status);return;}
            ProviderOperations.Owner dispatchOwner=owner;
            if(path.equals("/memory-extract")){
                HttpSession active=req.getSession(false);
                if(!owner.identity().startsWith("account:")||active==null||!(active.getAttribute("starhub.account.id") instanceof Long accountUser)||!(active.getAttribute("starhub.account.generation") instanceof String generation))throw new StorageFailure(401,"ACCOUNT_CHANGED","账户已更改。");
                if(!input.path("memoryRevision").isIntegralNumber()||!input.path("memoryRevision").canConvertToLong())throw new StorageFailure(400,"INVALID_MEMORY","记忆版本无效。");
                if(SemanticMemoryRequest.build(input.path("provider").asText(),input.path("model").asText(),input.path("apiKey").asText(),input.path("text").asText())==null)throw new StorageFailure(400,"MEMORY_SOURCE_INVALID","此内容不适合自动记忆。");
                long revision=input.path("memoryRevision").longValue();var originalOwner=owner;var originalInput=input;
                dispatchOwner=new ProviderOperations.Owner(owner.identity(),c->{originalOwner.verify().verify(c);AccountMemories.requireEnabledRevision(c,accountUser,generation,revision);SemanticMemorySource.require(c,accountUser,generation,originalInput);},owner.verifyCompletion(),owner.verifyNetwork());
            }
            if((path.equals("/run")||path.equals("/chat"))&&input.path("task").asText("chat").equals("chat")&&owner.identity().startsWith("account:")){
                HttpSession active=req.getSession(false);
                if(active==null||!(active.getAttribute("starhub.account.id") instanceof Long accountUser)||!(active.getAttribute("starhub.account.generation") instanceof String generation))throw new StorageFailure(401,"ACCOUNT_CHANGED","账户已更改。");
                MemoryPrompt.Snapshot memory=MemoryPrompt.read(service.atomic,owner,accountUser,generation);
                input=MemoryPrompt.apply((com.fasterxml.jackson.databind.node.ObjectNode)input.deepCopy(),memory);
                dispatchOwner=MemoryPrompt.guard(owner,memory);
            }
            if(input.path("task").asText().equals("image")&&!input.path("expectedEmail").asText().isBlank()&&!operation.equals(input.path("storageOperationId").asText()))throw new StorageFailure(400,"INVALID_OPERATION","图片保存必须使用原模型操作编号。");
            String kind=path+":"+input.path("action").asText("")+":"+input.path("task").asText("chat");
            ProviderOperations.Receipt claim=operations.claim(dispatchOwner,operation,kind,input);
            if(claim.state().equals("COMPLETED")){requireResultRead(req,input,owner);providerHeaders(res,claim);send(res,claim.httpStatus(),claim.result());return;}
            if(claim.state().equals("COMPLETED_NO_RESULT")){requireResultRead(req,input,owner);providerHeaders(res,claim);send(res,409,claim.result());return;}
            if(!claim.state().equals("CLAIMED")){providerFailure(res,409,"PROVIDER_OPERATION_UNKNOWN",operation,"模型操作可能已经执行。请查询原结果；新的操作可能再次计费。");return;}
            AccountServlet.providerIdle(service,req.getSession(false),!input.path("expectedEmail").asText().isBlank());
            ticket=operations.dispatch(dispatchOwner,operation,kind,input);res.setHeader("Cache-Control","no-store");res.setHeader("X-Starhub-Operation-ID",operation);res.setHeader("X-Starhub-Operation-State","SENT");res.setHeader("X-Four-Project-Durability",ticket.lease.mode());
            try(AutoCloseable context=ticket.enter()){
                runProvider(req,res,input,path,operations,owner,ticket);
            }
        }catch(Exception failure){
            if(ticket!=null){
                // Local validation before any real transport can be a known
                // terminal failure. A dispatched/uncertain call never retries.
                if(!ticket.attempted()&&failure instanceof ProviderOperations.NotDispatched denied){
                    try{var body=ModelGateway.JSON.createObjectNode().put("error",denied.getMessage()).put("code",denied.code);var done=ticket.complete(denied.status,body);providerHeaders(res,done);send(res,denied.status,done.result());return;}catch(Exception unknown){/* Keep the original operation uncertain. */}
                }
                if(!ticket.attempted()&&failure instanceof ModelGateway.Problem problem){
                    try{var body=ModelGateway.JSON.createObjectNode().put("error",problem.getMessage());var done=ticket.complete(problem.status,body);providerHeaders(res,done);send(res,problem.status,done.result());return;}catch(Exception unknown){/* Keep the original operation uncertain. */}
                }
                ticket.unknown();providerFailure(res,503,"PROVIDER_OPERATION_UNKNOWN",operation,"服务商可能已收到本次请求。请保留原操作编号并查询结果；不要自动重复调用。");return;
            }
            if(failure instanceof StorageFailure problem)providerFailure(res,problem.status,problem.code,operation,problem.getMessage());
            else if(failure instanceof ModelGateway.Problem problem)providerFailure(res,problem.status,"PROVIDER_REQUEST_REJECTED",operation,problem.getMessage());
            else if(failure instanceof JsonProcessingException)providerFailure(res,400,"PROVIDER_REQUEST_REJECTED",operation,"JSON 格式无效。");
            else providerFailure(res,503,"PROVIDER_OPERATION_UNKNOWN",operation,"模型操作结果尚未确认，请保留原编号后查询或重试。");
        }finally{admission.close();}
    }
    private void runProvider(HttpServletRequest req,HttpServletResponse res,com.fasterxml.jackson.databind.JsonNode request,String path,ProviderOperations operations,ProviderOperations.Owner owner,ProviderOperations.Ticket ticket)throws Exception {
        ResultReadGate readGate=resultReadGate(req,request,owner);
        com.fasterxml.jackson.databind.node.ObjectNode result;int status=200;
        if(path.equals("/memory-extract")){
            var extraction=SemanticMemoryRequest.build(request.path("provider").asText(),request.path("model").asText(),request.path("apiKey").asText(),request.path("text").asText());
            if(extraction==null)throw new ModelGateway.Problem(400,"此内容不适合自动记忆。");
            var proposed=gateway.chat(extraction);String value=proposed.path("text").asText();
            // Only a bounded result is persisted under this dedicated kind.
            result=ModelGateway.JSON.createObjectNode().put("text",value.length()<=8192?value:"{}");
        }
        else if(path.equals("/check")){result=new ConnectionCheck().run(request);if(result.path("status").asText().equals("rejected")){result.put("error","官方 API 拒绝此密钥，请检查密钥、账号和地区权限。");status=401;}}
        else if(path.equals("/batch"))result=batches.run(request);
        else {
            com.fasterxml.jackson.databind.JsonNode model=Catalog.find(request.path("provider").asText(),request.path("model").asText());
            if(model.path("batch").asBoolean())throw new ModelGateway.Problem(400,"请通过批处理面板提交此模型。");
            if(Catalog.contains(model.path("categories"),"realtime"))throw new ModelGateway.Problem(400,"请通过实时语音面板连接此模型。");
            String task=request.path("task").asText("chat");if(!Catalog.contains(model.path("categories"),task))throw new ModelGateway.Problem(400,"模型不支持所选任务。");
            if(task.equals("chat")&&request.path("provider").asText().equals("moonshot")&&KimiContext.supportsModel(request.path("model").asText())){
                com.fasterxml.jackson.databind.node.ObjectNode verified=(com.fasterxml.jackson.databind.node.ObjectNode)request.deepCopy();
                KimiContext.hydrate(verified,owner.identity(),KimiContext.configuredKey());request=verified;
            }
            SearchGateway.Result retrieved=task.equals("chat")?search.enrich((com.fasterxml.jackson.databind.node.ObjectNode)request):null;
            if(task.equals("chat"))request=MemoryPrompt.materialize((com.fasterxml.jackson.databind.node.ObjectNode)request);
            if(task.equals("chat")&&request.path("stream").asBoolean()){
                relayStream(res,ModelGateway.prepare(request,true),retrieved,operations,owner,ticket,readGate);return;
            }
            result=task.equals("chat")?gateway.chat(request):media.run(request);if(task.equals("image"))GeneratedImages.save(req,request,result);enrich(result,retrieved);
            if(result.has("providerReasoning"))KimiContext.protectConfiguredResult(result,owner.identity());
        }
        ProviderOperations.Receipt completed=ticket.complete(status,result);if(!readGate.refresh()){denyResultRead(res,ticket.operation,readGate.denial());return;}ProviderOperations.Receipt authorized=operations.status(owner,ticket.operation);providerHeaders(res,authorized);
        send(res,status,authorized.result());
    }
    void relayStream(HttpServletResponse res,ModelGateway.Prepared prepared,SearchGateway.Result retrieved,ProviderOperations operations,ProviderOperations.Owner owner,ProviderOperations.Ticket ticket,ResultReadGate readGate)throws Exception {
        StringBuilder text=new StringBuilder(),summary=new StringBuilder();var completed=ModelGateway.JSON.createObjectNode();boolean[] detached={false},notified={false};
        streams.run(prepared,(event,data)->{
            if(event.equals("delta"))text.append(data.path("text").asText());if(event.equals("reasoning"))summary.append(data.path("text").asText());
            if(event.equals("done")){completed.setAll(data);return;}
            if(detached[0])return;
            if(!readGate.allow()){if(!notified[0]){notified[0]=true;try{denyResultRead(res,ticket.operation,readGate.denial());}catch(IOException disconnected){detached[0]=true;}}return;}
            data.put("operationId",ticket.operation).put("operationState","SENT");try{event(res,event,data);}catch(IOException disconnected){detached[0]=true;}
        });
        completed.put("text",text.toString());if(!summary.isEmpty())completed.put("reasoningSummary",summary.toString());enrich(completed,retrieved);
        if(completed.has("providerReasoning"))KimiContext.protectConfiguredResult(completed,owner.identity());
        ticket.complete(200,completed);if(detached[0])return;if(!readGate.refresh()){if(!notified[0])denyResultRead(res,ticket.operation,readGate.denial());return;}
        ProviderOperations.Receipt durable=operations.status(owner,ticket.operation);providerHeaders(res,durable);var done=durable.result().deepCopy();done.remove(java.util.List.of("text","reasoningSummary"));done.put("durability",durable.durability());event(res,"done",done);
    }
    private ResultReadGate resultReadGate(HttpServletRequest req,com.fasterxml.jackson.databind.JsonNode input,ProviderOperations.Owner owner){
        if(!owner.identity().startsWith("account:"))return new ResultReadGate(()->Long.MAX_VALUE,()->true);
        String[] fields=owner.identity().split(":",4);String expected=input.path("expectedEmail").asText();HttpSession original=req.getSession(false);final long user;
        try{user=Long.parseLong(fields[1]);if(fields.length!=4)throw new IllegalArgumentException();}catch(Exception invalid){return new ResultReadGate(()->0,()->false);}
        byte[] hash;try{Object value=original.getAttribute("starhub.account.hash");hash=value instanceof byte[] bytes?bytes.clone():new byte[0];}catch(Exception invalid){hash=new byte[0];}
        final byte[] originalHash=hash;
        return new ResultReadGate(()->AccountServlet.providerIdle(FileStorage.get(),original,true),()->{
            try{Object current=original.getAttribute("starhub.account.hash");return java.util.Objects.equals(original.getAttribute("starhub.account.id"),user)&&java.util.Objects.equals(original.getAttribute("starhub.account.generation"),fields[2])&&java.util.Objects.equals(original.getAttribute("starhub.account.email"),expected)&&current instanceof byte[] bytes&&java.security.MessageDigest.isEqual(originalHash,bytes);}
            catch(Exception ended){return false;}
        });
    }
    private void denyResultRead(HttpServletResponse res,String operation,StorageFailure denial)throws IOException{
        providerFailure(res,denial.status,denial.code,operation,denial.getMessage());
    }
    private void requireResultRead(HttpServletRequest req,com.fasterxml.jackson.databind.JsonNode input,ProviderOperations.Owner owner){ResultReadGate gate=resultReadGate(req,input,owner);if(!gate.allow())throw gate.denial();}
    private void media(HttpServletRequest req,HttpServletResponse res,com.fasterxml.jackson.databind.JsonNode input,FileStorage service,ProviderOperations operations,String operation)throws Exception {
        if(!MEDIA_READS.tryAcquire())throw new StorageFailure(429,"PROVIDER_MEDIA_BUSY","图片读取繁忙，请保留原引用后重试。");
        try{
            String action=input.path("action").asText("read");if(!java.util.Set.of("read","import","import-status").contains(action))throw new StorageFailure(400,"INVALID_MEDIA_ACTION","图片操作无效。");
            if(action.equals("import")||action.equals("import-status")){
                if(!input.path("guestImport").asBoolean(false))throw new StorageFailure(400,"GUEST_IMPORT_REQUIRED","请明确选择原访客图片导入。");
                HttpSession session=req.getSession(false);ProviderOperations.Owner account=ProviderIdentity.from(session,input,service.atomic);if(session==null||!(session.getAttribute("starhub.account.id") instanceof Long user))throw new StorageFailure(401,"ACCOUNT_CHANGED","请先登录账户完成导入。");
                String importId=FileStorage.operationId(input.path("importOperationId").asText()),id=FileStorage.operationId(input.path("mediaId").asText());
                res.setHeader("X-Starhub-Import-Operation-ID",importId);
                var bridge=new ProviderImageImports(service.atomic,operations,(actor,op,files)->service.upload(req,actor,op,files));
                var result=bridge.execute(user,importId,operation,id,action.equals("import-status"),account,()->ProviderIdentity.guestImport(session,input,service.atomic));
                requireResultRead(req,input,account);res.setHeader("Cache-Control","no-store");res.setHeader("X-Starhub-Import-Operation-ID",importId);res.setHeader("X-Starhub-Import-State",result.body().path("state").asText());res.setHeader("X-Four-Project-Durability",result.durability());send(res,200,result.body());return;
            }
            ProviderOperations.Owner owner=input.path("guestImport").asBoolean(false)?ProviderIdentity.guestImport(req.getSession(false),input,service.atomic):ProviderIdentity.from(req.getSession(true),input,service.atomic);
            String id=FileStorage.operationId(input.path("mediaId").asText()),importId=input.path("importOperationId").asText();ProviderImageImports imports=null;ProviderOperations.Media image;
            if(!importId.isBlank()){
                if(input.path("guestImport").asBoolean(false))throw new StorageFailure(400,"INVALID_MEDIA_ACTION","已导入图片应使用当前账户只读身份。");FileStorage.operationId(importId);
                HttpSession session=req.getSession(false);if(session==null||!(session.getAttribute("starhub.account.id") instanceof Long user))throw new StorageFailure(401,"ACCOUNT_CHANGED","请重新登录原账户读取图片。");
                imports=new ProviderImageImports(service.atomic,operations,(actor,op,files)->service.upload(req,actor,op,files));var imported=imports.file(user,importId,operation,id,owner);image=new ProviderOperations.Media(null,imported.mime(),imported.id(),imported.durability());res.setHeader("X-Starhub-Import-Operation-ID",importId);
            }else image=operations.media(owner,operation,id);
            byte[] bytes=image.bytes();String mime=image.mime();
            if(image.fileId()!=null){HttpSession session=req.getSession(false);if(session==null||!(session.getAttribute("starhub.account.id") instanceof Long user))throw new StorageFailure(401,"ACCOUNT_CHANGED","请重新登录原账户读取图片。");
                var file=service.readBinary(user,id);bytes=file.bytes();if(!mime.equals(file.mime()))throw StorageFailure.unavailable();
                // Owner/session/password may have changed during the separate
                // library read. Reauthorize before exposing any response bytes.
                if(imports==null)image=operations.media(owner,operation,id);
                else {var imported=imports.file(user,importId,operation,id,owner);image=new ProviderOperations.Media(null,imported.mime(),imported.id(),imported.durability());res.setHeader("X-Starhub-Import-State","COMMITTED");}
            }
            if(bytes==null||bytes.length==0||bytes.length>GeneratedImages.MAX_IMAGE||!ProviderOperations.IMAGE_TYPES.contains(mime))throw StorageFailure.unavailable();
            ProviderOperations.Owner reader=input.path("guestImport").asBoolean(false)?ProviderIdentity.from(req.getSession(false),input,service.atomic):owner;requireResultRead(req,input,reader);
            res.setStatus(200);res.setContentType(mime);res.setContentLength(bytes.length);res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("X-Content-SHA256",java.util.HexFormat.of().formatHex(FileStorage.hash(bytes)));
            res.setHeader("X-Starhub-Operation-ID",operation);res.setHeader("X-Starhub-Operation-State","COMPLETED");res.setHeader("X-Four-Project-Durability",image.durability());res.setHeader("X-Four-Project-Degraded",Boolean.toString(image.durability().equals("single")));
            res.getOutputStream().write(bytes);
        }finally{MEDIA_READS.release();}
    }
    private static void enrich(com.fasterxml.jackson.databind.node.ObjectNode result,SearchGateway.Result retrieved){if(retrieved!=null){var details=result.with("details");details.put("webSearchUsed",true);details.set("webCitations",retrieved.citations);}}
    private void providerStatus(HttpServletResponse res,ProviderOperations.Receipt receipt)throws IOException {
        providerHeaders(res,receipt);var body=ModelGateway.JSON.createObjectNode().put("operationId",receipt.operationId()).put("operationState",receipt.state()).put("durability",receipt.durability());
        if(receipt.httpStatus()!=0)body.put("httpStatus",receipt.httpStatus());if(receipt.result()!=null)body.set("result",receipt.result());send(res,200,body);
    }
    private static void providerHeaders(HttpServletResponse res,ProviderOperations.Receipt receipt){
        if(res.isCommitted())return;res.setHeader("Cache-Control","no-store");res.setHeader("X-Starhub-Operation-ID",receipt.operationId());res.setHeader("X-Starhub-Operation-State",receipt.state());res.setHeader("X-Four-Project-Durability",receipt.durability());res.setHeader("X-Four-Project-Degraded",Boolean.toString(receipt.durability().equals("single")));
        if(receipt.result()!=null)receipt.result().put("durability",receipt.durability());
    }
    private void providerFailure(HttpServletResponse res,int status,String code,String operation,String message)throws IOException {
        if(!res.isCommitted())res.setHeader("Cache-Control","no-store");var body=ModelGateway.JSON.createObjectNode().put("error",message).put("code",code).put("operationState","UNKNOWN");if(operation!=null)body.put("operationId",operation);
        String importId=res.getHeader("X-Starhub-Import-Operation-ID");if(importId!=null){body.put("importOperationId",importId).put("state","UNKNOWN");res.setHeader("X-Starhub-Import-State","UNKNOWN");}
        if(res.isCommitted()){if(res.getContentType()!=null&&res.getContentType().startsWith("text/event-stream"))event(res,"error",body);return;}send(res,status,body);
    }
    private void error(HttpServletResponse res, int status, String message) throws IOException {
        if(res.isCommitted()) {
            if(res.getContentType()!=null && res.getContentType().startsWith("text/event-stream"))
                event(res,"error",ModelGateway.JSON.createObjectNode().put("error",message));
            return;
        }
        send(res, status, ModelGateway.JSON.createObjectNode().put("error", message));
    }
    static void event(HttpServletResponse res,String event,Object value)throws IOException {
        if(!res.isCommitted()) {
            res.setStatus(200);res.setContentType("text/event-stream;charset=UTF-8");
            res.setHeader("Cache-Control","no-store, no-transform");res.setHeader("X-Accel-Buffering","no");
        }
        byte[] bytes=("event: "+event+"\ndata: "+ModelGateway.JSON.writeValueAsString(value)+"\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        res.getOutputStream().write(bytes);res.flushBuffer();
    }
    private void send(HttpServletResponse res, int status, Object value) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        ModelGateway.JSON.writeValue(res.getOutputStream(), value);
    }
}
