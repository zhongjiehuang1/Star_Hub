package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/** Account-isolated conversations and bounded file storage. */
public final class WorkspaceServlet extends HttpServlet {
 static final long QUOTA=2_000_000_000L; static final int FILE_MAX=4*1024*1024;
 static final Set<String> TYPES=Set.of("image/png","image/jpeg","image/webp","audio/mpeg","audio/wav","audio/x-wav","audio/mp4","audio/webm","application/pdf","text/plain");
 private void send(HttpServletResponse r,int status,JsonNode data)throws IOException{r.setStatus(status);r.setContentType("application/json;charset=UTF-8");r.setHeader("Cache-Control","no-store");ModelGateway.JSON.writeValue(r.getOutputStream(),data);}
 private void fail(HttpServletResponse r,int status,String message)throws IOException{send(r,status,ModelGateway.JSON.createObjectNode().put("error",message));}
 protected void doPost(HttpServletRequest req,HttpServletResponse res)throws IOException{
  String origin=req.getHeader("Origin");
  if(!"web".equals(req.getHeader("X-Starhub-Client"))||!DeploymentConfig.allowsOrigin(origin)||req.getContentType()==null||!req.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")){fail(res,403,"请求来源无效。");return;}
  HttpSession session=req.getSession(false);if(!AccountServlet.activeSession(session)){fail(res,401,"请先登录。");return;}long user=(Long)session.getAttribute("starhub.account.id");
  byte[] bytes=req.getInputStream().readNBytes(6*1024*1024+1);if(bytes.length>6*1024*1024){fail(res,413,"请求超过限制。");return;}
  JsonNode body;try{body=ModelGateway.JSON.readTree(bytes);}catch(Exception e){fail(res,400,"请求无效。");return;}if(body==null||!body.isObject()){fail(res,400,"请求无效。");return;}
  if(!Objects.equals(session.getAttribute("starhub.account.email"),body.path("expectedEmail").asText())){fail(res,409,"账户已在另一个标签页更改，请刷新后重试。");return;}
  try(Connection c=AccountServlet.db(AccountServlet.config())){
   String action=body.path("action").asText();ObjectNode out=ModelGateway.JSON.createObjectNode();
   if(action.equals("listChats")){send(res,200,ConversationPages.load(c,user,(String)session.getAttribute("starhub.account.generation"),body));return;}
   if(action.equals("saveChat")){
    String id=body.path("id").asText(),title=body.path("title").asText(),payload=body.path("data").toString();long revision=body.path("revision").asLong(0);
    if(!id.matches("[A-Za-z0-9_-]{1,100}")||title.isBlank()||title.length()>200||!body.path("data").isObject()||payload.length()>1000000){fail(res,400,"聊天记录不符合限制。");return;}
    if(revision==0){c.setAutoCommit(false);try{
      try(PreparedStatement lock=c.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")){lock.setLong(1,user);try(ResultSet r=lock.executeQuery()){if(!r.next())throw new SQLException("user missing");}}
      try(PreparedStatement count=c.prepareStatement("SELECT COUNT(*) FROM conversations WHERE user_id=?")){count.setLong(1,user);try(ResultSet r=count.executeQuery()){r.next();if(r.getInt(1)>=500){c.rollback();fail(res,413,"每个账户最多保存 500 个聊天。 ");return;}}}
      try(PreparedStatement q=c.prepareStatement("INSERT IGNORE INTO conversations(user_id,chat_id,title,payload,revision) VALUES(?,?,?,?,1)")){q.setLong(1,user);q.setString(2,id);q.setString(3,title);q.setString(4,payload);if(q.executeUpdate()!=1){c.rollback();fail(res,409,"聊天记录已在其他页面更改，请重新加载。 ");return;}}c.commit();out.put("revision",1);
    }catch(Exception e){c.rollback();throw e;}}
    else{try(PreparedStatement q=c.prepareStatement("UPDATE conversations SET title=?,payload=?,revision=revision+1 WHERE user_id=? AND chat_id=? AND revision=?")){q.setString(1,title);q.setString(2,payload);q.setLong(3,user);q.setString(4,id);q.setLong(5,revision);if(q.executeUpdate()!=1){fail(res,409,"聊天记录已在其他页面更改，请重新加载。");return;}}out.put("revision",revision+1);}send(res,200,out);return;
   }
   if(action.equals("deleteChat")){try(PreparedStatement q=c.prepareStatement("DELETE FROM conversations WHERE user_id=? AND chat_id=? AND revision=?")){q.setLong(1,user);q.setString(2,body.path("id").asText());q.setLong(3,body.path("revision").asLong());if(q.executeUpdate()!=1){fail(res,409,"聊天记录已更改，请重新加载。");return;}}send(res,200,out.put("ok",true));return;}
   if(action.equals("listFiles")){send(res,200,FileStorage.get().list(user,body));return;}
   if(action.equals("reserveUpload")){
    JsonNode files=body.path("files");if(!files.isArray()||files.size()!=1||!files.get(0).path("size").isIntegralNumber()||files.get(0).path("size").asLong()>FILE_MAX){fail(res,400,"每次普通上传仅支持一个不超过 4 MiB 的文件。");return;}
    send(res,200,FileStorage.get().reserveUpload(req,user,body.path("operationId").asText(),files));return;
   }
   if(action.equals("cancelUpload")){send(res,200,FileStorage.get().cancelUpload(user,body.path("operationId").asText()));return;}
   if(action.equals("upload")){
    String name=body.path("name").asText(),mime=body.path("mime").asText(),encoded=body.path("data").asText();
    if(name.isBlank()||name.length()>255||name.indexOf('/')>=0||name.indexOf('\\')>=0||!TYPES.contains(mime)||encoded.length()>((FILE_MAX+2)/3)*4){fail(res,400,"文件类型或大小不受支持。");return;}
    byte[] content;try{content=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){fail(res,400,"文件格式无效。");return;}
    if(content.length==0||content.length>FILE_MAX){fail(res,400,"文件必须大于 0 且不超过 4 MiB。");return;}
    send(res,200,FileStorage.get().uploadReserved(req,user,body.path("operationId").asText(),List.of(new FileStorage.Upload(name,mime,content))));return;
   }
   if(action.equals("readFile")){send(res,200,FileStorage.get().read(user,body.path("id").asText()));return;}
   if(action.equals("trashFile")||action.equals("restoreFile")){send(res,200,FileStorage.get().trash(user,body.path("operationId").asText(),body.path("id").asText(),action.equals("restoreFile")));return;}
   if(action.equals("deleteFile")){send(res,200,FileStorage.get().delete(user,body.path("operationId").asText(),body.path("id").asText()));return;}
   if(action.equals("operationStatus")){send(res,200,FileStorage.get().status(user,body.path("operationId").asText()));return;}
   if(action.equals("operationSummary")){send(res,200,FileStorage.get().summary(user,body.path("operationId").asText()));return;}
   if(action.equals("requestStatus")){send(res,200,AtomicHttp.result(c,user,(String)session.getAttribute("starhub.account.generation"),body.path("operationId").asText()));return;}
   fail(res,400,"操作无效。");
  }catch(StorageFailure e){send(res,e.status,ModelGateway.JSON.createObjectNode().put("error",e.getMessage()).put("code",e.code));}
  catch(IllegalArgumentException e){fail(res,400,"请求参数无效。");}
  catch(Exception e){send(res,503,ModelGateway.JSON.createObjectNode().put("error","服务器故障，请等待恢复").put("code","CLUSTER_UNAVAILABLE"));}
 }
}
