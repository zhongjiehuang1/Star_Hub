package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/** Saves generated images directly to the same account-scoped library as uploads. */
final class GeneratedImages {
    static final int MAX_IMAGE=12*1024*1024;
    private static final Map<String,String> TYPES=Map.of("image/png","png","image/jpeg","jpg","image/webp","webp");
    private static final String FAILED="图片已生成，但未能保存到文件库；请在刷新前下载图片。";
    private static final String QUOTA="图片已生成，但账户文件库容量不足；请在刷新前下载图片。";
    static boolean fitsQuota(long used,long added){return used>=0&&added>=0&&added<=WorkspaceServlet.QUOTA&&used<=WorkspaceServlet.QUOTA-added;}
    private static final class Image {
        final ObjectNode media; final String mime,id; final byte[] bytes;
        Image(ObjectNode media,String mime,byte[] bytes){this.media=media;this.mime=mime;this.bytes=bytes;this.id=UUID.randomUUID().toString();}
    }
    private static List<Image> images(JsonNode media){
        List<Image> found=new ArrayList<>();
        for(JsonNode entry:media){
            if(!entry.isObject()||!"image".equals(entry.path("type").asText()))continue;
            String url=entry.path("url").asText();int comma=url.indexOf(',');
            if(comma<0||!url.startsWith("data:")||!url.substring(0,comma).endsWith(";base64"))throw new IllegalArgumentException();
            String mime=url.substring(5,comma-7);if(!TYPES.containsKey(mime))throw new IllegalArgumentException();
            String encoded=url.substring(comma+1);if(encoded.length()>((MAX_IMAGE+2)/3)*4)throw new IllegalArgumentException();
            byte[] data=Base64.getDecoder().decode(encoded);if(data.length==0||data.length>MAX_IMAGE)throw new IllegalArgumentException();
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
            try(Connection c=AccountServlet.db(AccountServlet.config())){
                c.setAutoCommit(false);
                try {
                    try(PreparedStatement q=c.prepareStatement("SELECT id FROM users WHERE id=? AND email=? AND active=1 FOR UPDATE")){q.setLong(1,user);q.setString(2,expected);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException();}}
                    long used;try(PreparedStatement q=c.prepareStatement("SELECT COALESCE(SUM(size_bytes),0) FROM library_files WHERE user_id=?")){q.setLong(1,user);try(ResultSet r=q.executeQuery()){r.next();used=r.getLong(1);}}
                    long total=list.stream().mapToLong(i->i.bytes.length).sum();
                    if(!fitsQuota(used,total)){c.rollback();result.put("warning",QUOTA);result.put("imageSaveFailed",true);return;}
                    try(PreparedStatement q=c.prepareStatement("INSERT INTO library_files(id,user_id,name,mime,size_bytes,content) VALUES(?,?,?,?,?,?)")){
                        for(Image i:list){q.setString(1,i.id);q.setLong(2,user);q.setString(3,"Star-Hub-"+i.id+"."+TYPES.get(i.mime));q.setString(4,i.mime);q.setInt(5,i.bytes.length);q.setBytes(6,i.bytes);q.addBatch();}q.executeBatch();
                    }
                    c.commit();for(Image i:list)i.media.put("fileId",i.id);
                } catch(Exception e){c.rollback();throw e;}
            }
        } catch(Exception e){result.put("warning",FAILED);result.put("imageSaveFailed",true);}
    }
}
