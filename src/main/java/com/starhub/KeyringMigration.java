package com.starhub;
import java.sql.*;
import java.util.*;
/** Operator-only migration; no credentials in arguments. */
public final class KeyringMigration {
 public static void main(String[] args)throws Exception{
  Properties p=AccountServlet.config();byte[] vault=Base64.getDecoder().decode(p.getProperty("vault.key"));
  try(Connection c=AccountServlet.db(p)){
   java.util.List<Long> users=new ArrayList<>();try(PreparedStatement q=c.prepareStatement(args.length==1?"SELECT DISTINCT user_id FROM provider_keys WHERE user_id=?":"SELECT DISTINCT user_id FROM provider_keys")){if(args.length==1)q.setLong(1,Long.parseLong(args[0]));try(ResultSet r=q.executeQuery()){while(r.next())users.add(r.getLong(1));}}
   c.setAutoCommit(false);try{for(long user:users)Keyring.migrate(c,vault,user);c.commit();}catch(Exception e){c.rollback();throw e;}System.out.println("Migrated accounts: "+users.size());
  }
 }
}
