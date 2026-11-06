package com.starhub;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.concurrent.Semaphore;
import javax.servlet.http.*;

public final class ApiServlet extends HttpServlet {
    private final ModelGateway gateway = new ModelGateway();
    private final ChatStream streams = new ChatStream();
    private final SearchGateway search = new SearchGateway();
    private final Semaphore slots = new Semaphore(2);
    private final MediaGateway media = new MediaGateway();
    private final BatchGateway batches = new BatchGateway();

    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        switch (String.valueOf(req.getPathInfo())) {
            case "/health": send(res, 200, ModelGateway.JSON.createObjectNode().put("status", "ok").put("app", "Star Hub")); break;
            case "/catalog": send(res, 200, Catalog.publicData()); break;
            case "/source":
                try { send(res,200,CallSources.read(req.getParameter("file"))); }
                catch(ModelGateway.Problem e){error(res,e.status,e.getMessage());}
                catch(IOException e){error(res,503,"本版本源码暂不可读取，请稍后重试。");}
                break;
            case "/providers": send(res, 200, ModelGateway.catalogue()); break;
            default: error(res, 404, "接口不存在。");
        }
    }
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (!"/chat".equals(req.getPathInfo()) && !"/run".equals(req.getPathInfo()) && !"/batch".equals(req.getPathInfo()) && !"/check".equals(req.getPathInfo())) { error(res, 404, "接口不存在。"); return; }
        if (!"web".equals(req.getHeader("X-Starhub-Client")) || req.getContentType() == null || !req.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            error(res, 415, "请使用本站的 JSON 聊天接口。"); return;
        }
        String origin = req.getHeader("Origin");
        String expected = System.getenv().getOrDefault("STARHUB_PUBLIC_ORIGIN", "https://starhub.zhongjiehuang.com");
        if (origin != null && !origin.equals(expected)) { error(res, 403, "请求来源不受支持。"); return; }
        if (!slots.tryAcquire()) { error(res, 429, "当前请求较多，请稍后重试。"); return; }
        try {
            byte[] input = req.getInputStream().readNBytes(6 * 1024 * 1024 + 1);
            if (input.length > 6 * 1024 * 1024) { error(res, 413, "请求过大，请新建对话。"); return; }
            com.fasterxml.jackson.databind.JsonNode request = ModelGateway.JSON.readTree(input);
            if(request==null||!request.isObject())throw new ModelGateway.Problem(400,"请求格式无效。");
            if("/check".equals(req.getPathInfo())){
                com.fasterxml.jackson.databind.node.ObjectNode checked=new ConnectionCheck().run(request);
                if(checked.path("status").asText().equals("rejected")){checked.put("error","官方 API 拒绝此密钥，请检查密钥、账号和地区权限。");send(res,401,checked);}else send(res,200,checked);return;
            }
            if("/batch".equals(req.getPathInfo())){send(res,200,batches.run(request));return;}
            com.fasterxml.jackson.databind.JsonNode model = Catalog.find(request.path("provider").asText(),request.path("model").asText());
            if(model.path("batch").asBoolean())throw new ModelGateway.Problem(400,"请通过批处理面板提交此模型。");
            if(Catalog.contains(model.path("categories"),"realtime"))throw new ModelGateway.Problem(400,"请通过实时语音面板连接此模型。");
            String task=request.path("task").asText("chat");
            if(!Catalog.contains(model.path("categories"),task))throw new ModelGateway.Problem(400,"模型不支持所选任务。");
            SearchGateway.Result retrieved=task.equals("chat")?search.enrich((com.fasterxml.jackson.databind.node.ObjectNode)request):null;
            if(task.equals("chat") && request.path("stream").asBoolean()) {
                ModelGateway.Prepared prepared=ModelGateway.prepare(request,true);
                streams.run(prepared,(kind,data)->{if(kind.equals("done")&&retrieved!=null){com.fasterxml.jackson.databind.node.ObjectNode details=data.with("details");details.put("webSearchUsed",true);details.set("webCitations",retrieved.citations);}event(res,kind,data);});
            } else {com.fasterxml.jackson.databind.node.ObjectNode result=task.equals("chat")?gateway.chat(request):media.run(request);if(task.equals("image"))GeneratedImages.save(req,request,result);if(retrieved!=null){com.fasterxml.jackson.databind.node.ObjectNode details=result.with("details");details.put("webSearchUsed",true);details.set("webCitations",retrieved.citations);}send(res,200,result);}
        } catch (ModelGateway.Problem e) { error(res, e.status, e.getMessage());
        } catch (JsonProcessingException e) { error(res, 400, "JSON 格式不正确或服务商响应无法解析。");
        } catch (SocketTimeoutException e) { error(res, 504, "模型响应超时，请稍后重试。");
        } catch (IOException e) { error(res, 502, "无法连接模型服务，请稍后重试。");
        } catch (RuntimeException e) { error(res, 500, "请求处理失败，请检查设置后重试。");
        } finally { slots.release(); }
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
            res.setHeader("Cache-Control","no-cache, no-transform");res.setHeader("X-Accel-Buffering","no");
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
