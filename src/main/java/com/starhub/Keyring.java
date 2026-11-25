package com.starhub;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Encrypted account-scoped key ring. HMAC enables uniqueness without plaintext keys. */
final class Keyring {
 static byte[] digest(byte[] vault,String key)throws GeneralSecurityException {Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(vault,"HmacSHA256"));m.update("starhub-keyring-v1\0".getBytes(StandardCharsets.UTF_8));return m.doFinal(key.getBytes(StandardCharsets.UTF_8));}
 static void lock(Connection c,long user)throws SQLException{try(PreparedStatement q=c.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")){q.setLong(1,user);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("User unavailable");}}}
 static String add(Connection c,long user,String provider,String label,String value,byte[] vault)throws Exception{
  String id=UUID.randomUUID().toString();byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);byte[] encrypted=AccountServlet.crypt(true,value.getBytes(StandardCharsets.UTF_8),vault,nonce,user+":"+provider+":"+id);
  try(PreparedStatement q=c.prepareStatement("INSERT INTO api_keys(id,user_id,provider,label,key_digest,nonce,ciphertext,selected) VALUES(?,?,?,?,?,?,?,FALSE)")){q.setString(1,id);q.setLong(2,user);q.setString(3,provider);q.setString(4,label);q.setBytes(5,digest(vault,value));q.setBytes(6,nonce);q.setBytes(7,encrypted);q.executeUpdate();}return id;
 }
 static void select(Connection c,long user,String provider,String id)throws SQLException{
  try(PreparedStatement q=c.prepareStatement("UPDATE api_keys SET selected=FALSE WHERE user_id=? AND provider=?")){q.setLong(1,user);q.setString(2,provider);q.executeUpdate();}
  try(PreparedStatement q=c.prepareStatement("UPDATE api_keys SET selected=TRUE WHERE user_id=? AND provider=? AND id=?")){q.setLong(1,user);q.setString(2,provider);q.setString(3,id);if(q.executeUpdate()!=1)throw new SQLException("Key not found","45000");}
 }
 static String decrypt(long user,String provider,String id,byte[] nonce,byte[] ciphertext,byte[] vault)throws GeneralSecurityException{return new String(AccountServlet.crypt(false,ciphertext,vault,nonce,user+":"+provider+":"+id),StandardCharsets.UTF_8);}
 static void migrate(Connection c,byte[] vault,long user)throws Exception{
  lock(c,user);try(PreparedStatement q=c.prepareStatement("SELECT provider,nonce,ciphertext FROM provider_keys WHERE user_id=? FOR UPDATE")){q.setLong(1,user);try(ResultSet r=q.executeQuery()){while(r.next()){String provider=r.getString(1);String value=new String(AccountServlet.crypt(false,r.getBytes(3),vault,r.getBytes(2),user+":"+provider),StandardCharsets.UTF_8);String id=null;try(PreparedStatement existing=c.prepareStatement("SELECT id,provider FROM api_keys WHERE user_id=? AND key_digest=?")){existing.setLong(1,user);existing.setBytes(2,digest(vault,value));try(ResultSet found=existing.executeQuery()){if(found.next()){if(!provider.equals(found.getString(2)))throw new SQLException("Duplicate legacy key across providers","23000");id=found.getString(1);}}}if(id==null)id=add(c,user,provider,"Default",value,vault);select(c,user,provider,id);}}}try(PreparedStatement q=c.prepareStatement("DELETE FROM provider_keys WHERE user_id=?")){q.setLong(1,user);q.executeUpdate();}
 }
}
