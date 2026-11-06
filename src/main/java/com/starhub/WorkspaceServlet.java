package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import javax.servlet.http.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/** Account-isolated conversations and bounded file storage. */
public final class WorkspaceServlet extends HttpServlet {
 static final long QUOTA=2L*1024*1024*1024; static final int FILE_MAX=4*1024*1024;
 static final Set<String> TYPES=Set.of("image/png","image/jpeg","image/webp","audio/mpeg","audio/wav","audio/x-wav","audio/mp4","audio/webm","application/pdf","text/plain");
 private void send(HttpServletResponse r,int status,JsonNode data)throws IOException{r.setStatus(status);r.setContentType("application/json;charset=UTF-8");r.setHeader("Cache-Control","no-store");ModelGateway.JSON.writeValue(r.getOutputStream(),data);}
 private void fail(HttpServletResponse r,int status,String message)throws IOException{send(r,status,ModelGateway.JSON.createObjectNode().put("error",message));}
 protected void doPost(HttpServletRequest req,HttpServletResponse res)throws IOException{
  String origin=req.getHeader("Origin"),expected=System.getenv().getOrDefault("STARHUB_PUBLIC_ORIGIN","https://starhub.zhongjiehuang.com");
  if(!"web".equals(req.getHeader("X-Starhub-Client"))||origin==null||!origin.equals(expected)||req.getContentType()==null||!req.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")){fail(res,403,"请求来源无效。");return;}
  HttpSession session=req.getSession(false);if(!AccountServlet.activeSession(session)){fail(res,401,"请先登录。");return;}long user=(Long)session.getAttribute("starhub.account.id");
  byte[] bytes=req.getInputStream().readNBytes(6*1024*1024+1);if(bytes.length>6*1024*1024){fail(res,413,"请求超过限制。");return;}
  JsonNode body;try{body=ModelGateway.JSON.readTree(bytes);}catch(Exception e){fail(res,400,"请求无效。");return;}if(body==null||!body.isObject()){fail(res,400,"请求无效。");return;}
  if(!Objects.equals(session.getAttribute("starhub.account.email"),body.path("expectedEmail").asText())){fail(res,409,"账户已在另一个标签页更改，请刷新后重试。");return;}
  try(Connection c=AccountServlet.db(AccountServlet.config())){
   String action=body.path("action").asText();ObjectNode out=ModelGateway.JSON.createObjectNode();
   if(action.equals("listChats")){ArrayNode a=out.putArray("chats");try(PreparedStatement q=c.prepareStatement("SELECT chat_id,title,payload,revision FROM conversations WHERE user_id=? ORDER BY updated_at DESC LIMIT 500")){q.setLong(1,user);try(ResultSet r=q.executeQuery()){while(r.next()){ObjectNode v=a.addObject().put("id",r.getString(1)).put("title",r.getString(2)).put("revision",r.getLong(4));v.set("data",ModelGateway.JSON.readTree(r.getString(3)));}}}send(res,200,out);return;}
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
   if(action.equals("listFiles")){ArrayNode a=out.putArray("files");long used=0;try(PreparedStatement q=c.prepareStatement("SELECT id,name,mime,size_bytes,deleted_at IS NOT NULL FROM library_files WHERE user_id=? ORDER BY created_at DESC LIMIT 5000")){q.setLong(1,user);try(ResultSet r=q.executeQuery()){while(r.next()){int size=r.getInt(4);used+=size;a.addObject().put("id",r.getString(1)).put("name",r.getString(2)).put("mime",r.getString(3)).put("size",size).put("trashed",r.getBoolean(5));}}}try(PreparedStatement q=c.prepareStatement("SELECT COALESCE(SUM(size_bytes),0) FROM library_files WHERE user_id=?")){q.setLong(1,user);try(ResultSet r=q.executeQuery()){r.next();used=r.getLong(1);}}out.put("used",used).put("quota",QUOTA).put("maxFile",FILE_MAX);send(res,200,out);return;}
   if(action.equals("upload")){
    String name=body.path("name").asText(),mime=body.path("mime").asText(),encoded=body.path("data").asText();if(name.isBlank()||name.length()>255||name.indexOf('/')>=0||name.indexOf('\\')>=0||!TYPES.contains(mime)||encoded.length()>((FILE_MAX+2)/3)*4){fail(res,400,"文件类型或大小不受支持。");return;}
    byte[] content;try{content=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){fail(res,400,"文件格式无效。");return;}if(content.length==0||content.length>FILE_MAX){fail(res,400,"文件必须大于 0 且不超过 4 MiB。");return;}
    c.setAutoCommit(false);try{try(PreparedStatement lock=c.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")){lock.setLong(1,user);try(ResultSet r=lock.executeQuery()){if(!r.next())throw new SQLException("user missing");}}long total;try(PreparedStatement q=c.prepareStatement("SELECT COALESCE(SUM(size_bytes),0) FROM library_files WHERE user_id=?")){q.setLong(1,user);try(ResultSet r=q.executeQuery()){r.next();total=r.getLong(1);}}if(total+content.length>QUOTA){c.rollback();fail(res,413,"账户文件库已达到 2 GiB 上限（含回收箱）。");return;}
    String id=UUID.randomUUID().toString();try(PreparedStatement q=c.prepareStatement("INSERT INTO library_files(id,user_id,name,mime,size_bytes,content) VALUES(?,?,?,?,?,?)")){q.setString(1,id);q.setLong(2,user);q.setString(3,name);q.setString(4,mime);q.setInt(5,content.length);q.setBytes(6,content);q.executeUpdate();}c.commit();send(res,200,out.put("id",id));return;}catch(Exception e){c.rollback();throw e;}
   }
   if(action.equals("readFile")){try(PreparedStatement q=c.prepareStatement("SELECT name,mime,content FROM library_files WHERE id=? AND user_id=? AND deleted_at IS NULL")){q.setString(1,body.path("id").asText());q.setLong(2,user);try(ResultSet r=q.executeQuery()){if(!r.next()){fail(res,404,"文件不存在。");return;}out.put("name",r.getString(1)).put("mime",r.getString(2)).put("data",Base64.getEncoder().encodeToString(r.getBytes(3)));}}send(res,200,out);return;}
   if(Set.of("trashFile","restoreFile","deleteFile").contains(action)){String sql=action.equals("trashFile")?"UPDATE library_files SET deleted_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND deleted_at IS NULL":action.equals("restoreFile")?"UPDATE library_files SET deleted_at=NULL WHERE id=? AND user_id=? AND deleted_at IS NOT NULL":"DELETE FROM library_files WHERE id=? AND user_id=? AND deleted_at IS NOT NULL";try(PreparedStatement q=c.prepareStatement(sql)){q.setString(1,body.path("id").asText());q.setLong(2,user);if(q.executeUpdate()!=1){fail(res,404,"文件不存在或状态已更改。");return;}}send(res,200,out.put("ok",true));return;}
   fail(res,400,"操作无效。");
  }catch(Exception e){fail(res,503,"账户存储暂时不可用，请稍后重试。");}
 }
}
