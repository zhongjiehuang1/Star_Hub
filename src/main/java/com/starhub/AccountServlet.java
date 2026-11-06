package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import javax.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.KeySpec;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Invitation-only accounts. Credentials are never placed in source or logs. */
public final class AccountServlet extends HttpServlet {
    static final int SESSION_SECONDS=600;
    private static final String USER="starhub.account.id", EMAIL="starhub.account.email";
    private static final Map<String, long[]> FAILURES=new ConcurrentHashMap<>();
    static Properties config() throws IOException {Properties p=new Properties();try(InputStream i=Files.newInputStream(Paths.get("/var/lib/starhub/account-config.properties"))){p.load(i);}return p;}
    static Connection db(Properties p)throws SQLException{try{Class.forName("com.mysql.cj.jdbc.Driver",true,AccountServlet.class.getClassLoader());}catch(ClassNotFoundException e){throw new SQLException("Database driver unavailable",e);}return DriverManager.getConnection(p.getProperty("db.url"),p.getProperty("db.user"),p.getProperty("db.password"));}
    static boolean activeSession(HttpSession s) {
        if(s==null||!(s.getAttribute(USER) instanceof Long)||!(s.getAttribute("starhub.account.hash") instanceof byte[]))return false;
        try(Connection c=db(config());PreparedStatement q=c.prepareStatement("SELECT password_hash,email FROM users WHERE id=? AND active=1")){
            q.setLong(1,(Long)s.getAttribute(USER));try(ResultSet r=q.executeQuery()){return r.next()&&MessageDigest.isEqual((byte[])s.getAttribute("starhub.account.hash"),r.getBytes(1))&&Objects.equals(s.getAttribute(EMAIL),r.getString(2));}
        }catch(Exception e){return false;}
    }
    static byte[] hash(String password,byte[] salt)throws GeneralSecurityException {KeySpec spec=new PBEKeySpec(password.toCharArray(),salt,600000,256);return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
    static byte[] crypt(boolean encrypt,byte[] input,byte[] key,byte[] iv,String aad)throws GeneralSecurityException {Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));return c.doFinal(input);}
    private void respond(HttpServletResponse r,int status,Object value)throws IOException {r.setStatus(status);r.setContentType("application/json;charset=UTF-8");r.setHeader("Cache-Control","no-store");ModelGateway.JSON.writeValue(r.getOutputStream(),value);}
    private void fail(HttpServletResponse r,int status,String text)throws IOException {respond(r,status,ModelGateway.JSON.createObjectNode().put("error",text));}
    protected void doGet(HttpServletRequest req,HttpServletResponse res)throws IOException {
        HttpSession session=req.getSession(false);if(!activeSession(session)){respond(res,200,ModelGateway.JSON.createObjectNode().put("signedIn",false));return;}
        try {Properties p=config();long id=(Long)session.getAttribute(USER);ObjectNode out=ModelGateway.JSON.createObjectNode().put("signedIn",true).put("email",(String)session.getAttribute(EMAIL));ObjectNode keys=out.putObject("keys");
            ArrayNode ring=out.putArray("keyRing");byte[] vault=Base64.getDecoder().decode(p.getProperty("vault.key"));
            try(Connection c=db(p)){c.setAutoCommit(false);try{Keyring.migrate(c,vault,id);c.commit();}catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}try(PreparedStatement q=c.prepareStatement("SELECT id,provider,label,nonce,ciphertext,selected FROM api_keys WHERE user_id=? ORDER BY created_at,id")){q.setLong(1,id);try(ResultSet r=q.executeQuery()){while(r.next()){String keyId=r.getString(1),provider=r.getString(2);boolean selected=r.getBoolean(6);ring.addObject().put("id",keyId).put("provider",provider).put("label",r.getString(3)).put("selected",selected);if(selected)keys.put(provider,Keyring.decrypt(id,provider,keyId,r.getBytes(4),r.getBytes(5),vault));}}}}respond(res,200,out);
        }catch(Exception e){getServletContext().log("Star Hub account backend failure: "+e.getClass().getSimpleName()+(e instanceof SQLException?" SQLState="+((SQLException)e).getSQLState():""));fail(res,503,"账户服务暂时不可用。");}
    }
    private byte[] hashedForSession(HttpSession s)throws Exception {try(Connection c=db(config());PreparedStatement q=c.prepareStatement("SELECT password_hash FROM users WHERE id=?")){q.setLong(1,(Long)s.getAttribute(USER));try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException();return r.getBytes(1);}}}
    protected void doPost(HttpServletRequest req,HttpServletResponse res)throws IOException {
        String origin=req.getHeader("Origin"),expected=System.getenv().getOrDefault("STARHUB_PUBLIC_ORIGIN","https://starhub.zhongjiehuang.com");
        if(!"web".equals(req.getHeader("X-Starhub-Client"))||origin==null||!origin.equals(expected)||req.getContentType()==null||!req.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")){fail(res,403,"请求来源无效。");return;}
        byte[] data=req.getInputStream().readNBytes(16385);if(data.length>16384){fail(res,413,"请求过大。");return;}
        JsonNode body;try{body=ModelGateway.JSON.readTree(data);}catch(Exception e){fail(res,400,"请求无效。");return;}if(body==null||!body.isObject()){fail(res,400,"请求无效。");return;}
        String action=body.path("action").asText();HttpSession session=req.getSession(false);
        if(action.equals("logout")){if(session!=null&&session.getAttribute(USER)!=null&&!Objects.equals(session.getAttribute(EMAIL),body.path("expectedEmail").asText())){fail(res,409,"账户已在另一个标签页更改，请刷新后重试。");return;}if(session!=null){session.removeAttribute(USER);session.removeAttribute(EMAIL);req.changeSessionId();}respond(res,200,ModelGateway.JSON.createObjectNode().put("ok",true));return;}
        try {Properties p=config();
            if(action.equals("login")){
                String ip=req.getHeader("X-Real-IP");if(ip==null)ip=req.getRemoteAddr();final String address=ip;long now=System.currentTimeMillis();long[] attempts=FAILURES.computeIfAbsent(address,k->new long[]{0,now});synchronized(attempts){if(now-attempts[1]>900000){attempts[0]=0;attempts[1]=now;}if(attempts[0]>=8){fail(res,429,"尝试次数过多，请稍后再试。");return;}attempts[0]++;}
                String email=body.path("email").asText("").trim().toLowerCase(Locale.ROOT),password=body.path("password").asText("");if(email.length()>254||password.length()>256){fail(res,401,"邮箱或密码不正确。");return;}
                byte[] salt=new byte[16],expectedHash=new byte[32];long id=0;try(Connection c=db(p);PreparedStatement q=c.prepareStatement("SELECT id, password_salt, password_hash FROM users WHERE email=? AND active=1")){q.setString(1,email);try(ResultSet r=q.executeQuery()){if(r.next()){id=r.getLong(1);salt=r.getBytes(2);expectedHash=r.getBytes(3);}}}
                if(!MessageDigest.isEqual(hash(password,salt),expectedHash)||id==0){fail(res,401,"邮箱或密码不正确。");return;}
                FAILURES.remove(address);session=req.getSession(true);req.changeSessionId();session.setAttribute(USER,id);session.setAttribute(EMAIL,email);session.setAttribute("starhub.account.hash",expectedHash);session.setMaxInactiveInterval(SESSION_SECONDS);respond(res,200,ModelGateway.JSON.createObjectNode().put("ok",true));return;
            }
            if(!activeSession(session)){fail(res,401,"请先登录。");return;}
            if(!Objects.equals(session.getAttribute(EMAIL),body.path("expectedEmail").asText())){fail(res,409,"账户已在另一个标签页更改，请刷新后重试。");return;}
            if(action.equals("changePassword")){
                long now=System.currentTimeMillis();long[] attempts=(long[])session.getAttribute("starhub.password.attempts");
                if(attempts==null||now-attempts[1]>900000){attempts=new long[]{0,now};session.setAttribute("starhub.password.attempts",attempts);}
                synchronized(attempts){if(attempts[0]>=8){fail(res,429,"尝试次数过多，请稍后再试。");return;}attempts[0]++;}
                String old=body.path("currentPassword").asText(""),next=body.path("newPassword").asText("");
                if(next.length()<12||next.length()>256||old.length()>256){fail(res,400,"新密码须为 12–256 个字符。");return;}
                long id=(Long)session.getAttribute(USER);try(Connection c=db(p);PreparedStatement q=c.prepareStatement("SELECT password_salt,password_hash FROM users WHERE id=? AND active=1")){
                    q.setLong(1,id);try(ResultSet r=q.executeQuery()){if(!r.next()||!MessageDigest.isEqual(hash(old,r.getBytes(1)),r.getBytes(2))){fail(res,403,"当前密码不正确。");return;}}
                    byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);byte[] hashed=hash(next,salt);
                    try(PreparedStatement u=c.prepareStatement("UPDATE users SET password_salt=?,password_hash=? WHERE id=?")){u.setBytes(1,salt);u.setBytes(2,hashed);u.setLong(3,id);u.executeUpdate();}
                }session.removeAttribute("starhub.password.attempts");session.setAttribute("starhub.account.hash",hashedForSession(session));req.changeSessionId();respond(res,200,ModelGateway.JSON.createObjectNode().put("ok",true));return;
            }
            if(action.equals("addKey")||action.equals("selectKey")||action.equals("deleteKey")){
                String provider=body.path("provider").asText(),id=body.path("id").asText(""),value=body.path("key").asText("").trim(),label=body.path("label").asText("").trim();long user=(Long)session.getAttribute(USER);
                if(!ModelGateway.PROVIDERS.containsKey(provider)||(!action.equals("addKey")&&!id.matches("[0-9a-f-]{36}"))||(action.equals("addKey")&&(label.isBlank()||label.length()>120||value.length()>4096||!value.matches("[\\x21-\\x7e]+")))){fail(res,400,"密钥信息无效。");return;}
                try(Connection c=db(p)){c.setAutoCommit(false);try{Keyring.migrate(c,Base64.getDecoder().decode(p.getProperty("vault.key")),user);
                    if(action.equals("addKey")){try(PreparedStatement count=c.prepareStatement("SELECT COUNT(*) FROM api_keys WHERE user_id=?")){count.setLong(1,user);try(ResultSet r=count.executeQuery()){r.next();if(r.getInt(1)>=100){c.rollback();fail(res,400,"每个账户最多保存 100 个密钥。");return;}}}id=Keyring.add(c,user,provider,label,value,Base64.getDecoder().decode(p.getProperty("vault.key")));Keyring.select(c,user,provider,id);}
                    else if(action.equals("selectKey"))Keyring.select(c,user,provider,id);
                    else{try(PreparedStatement q=c.prepareStatement("DELETE FROM api_keys WHERE id=? AND user_id=? AND provider=?")){q.setString(1,id);q.setLong(2,user);q.setString(3,provider);if(q.executeUpdate()!=1){c.rollback();fail(res,404,"密钥不存在。");return;}}}
                    c.commit();respond(res,200,ModelGateway.JSON.createObjectNode().put("ok",true));return;
                }catch(SQLIntegrityConstraintViolationException e){c.rollback();fail(res,409,"该密钥已存在于此账户。");return;}catch(SQLException e){c.rollback();if("45000".equals(e.getSQLState())){fail(res,404,"密钥不存在。");return;}throw e;}catch(Exception e){c.rollback();throw e;}}
            }
            if(action.equals("saveKey")){fail(res,409,"密钥管理已更新，请刷新页面。");return;}
            fail(res,400,"操作无效。");
        }catch(Exception e){getServletContext().log("Star Hub account backend failure: "+e.getClass().getSimpleName()+(e instanceof SQLException?" SQLState="+((SQLException)e).getSQLState():""));fail(res,503,"账户服务暂时不可用。");}
    }
}
