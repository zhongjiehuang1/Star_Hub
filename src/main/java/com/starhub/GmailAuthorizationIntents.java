package com.starhub;

import java.security.GeneralSecurityException;
import java.sql.*;
import java.util.Set;

/** Durable, account-scoped coordination for a single non-replayable OAuth exchange.
 * All methods execute inside the same certified atomic database scope as credentials. */
final class GmailAuthorizationIntents {
    private static final Set<String> STATES=Set.of("AUTHORIZING","EXCHANGING","CANCEL_PENDING","UNKNOWN","CONNECTED","REVOKING");
    record Intent(String operation,String status) {
        Intent {if(operation==null||!operation.matches("[0-9a-f-]{36}")||!STATES.contains(status))throw new IllegalArgumentException();}
    }
    static Intent read(Connection c,GmailCredentialStore.Owner owner)throws SQLException {
        GmailCredentialStore.checkOwner(c,owner,false);
        try(PreparedStatement q=c.prepareStatement("SELECT operation_id,status FROM starhub_gmail_authorizations WHERE user_id=? AND owner_generation=?")){
            q.setLong(1,owner.userId());q.setString(2,owner.generation());try(ResultSet r=q.executeQuery()){return r.next()?new Intent(r.getString(1),r.getString(2)):null;}}
    }
    static void begin(Connection c,GmailCredentialStore.Owner owner,String operation,long now)throws SQLException {
        new Intent(operation,"AUTHORIZING");GmailCredentialStore.checkOwner(c,owner,true);
        if(now<0||read(c,owner)!=null||GmailCredentialStore.read(c,owner)!=null)throw new SQLException("A Gmail connection or authorization already exists","40001");
        try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_gmail_authorizations(user_id,owner_generation,operation_id,status,updated_millis) VALUES(?,?,?,?,?)")){
            q.setLong(1,owner.userId());q.setString(2,owner.generation());q.setString(3,operation);q.setString(4,"AUTHORIZING");q.setLong(5,now);q.executeUpdate();}
    }
    private static void state(Connection c,GmailCredentialStore.Owner owner,Intent prior,String next,long now)throws SQLException {
        if(now<0||!STATES.contains(next))throw new IllegalArgumentException();
        try(PreparedStatement q=c.prepareStatement("UPDATE starhub_gmail_authorizations SET status=?,updated_millis=? WHERE user_id=? AND owner_generation=? AND operation_id=? AND status=?")){
            q.setString(1,next);q.setLong(2,now);q.setLong(3,owner.userId());q.setString(4,owner.generation());q.setString(5,prior.operation());q.setString(6,prior.status());
            if(q.executeUpdate()!=1)throw new SQLException("Gmail authorization changed","40001");}
    }
    static void exchange(Connection c,GmailCredentialStore.Owner owner,String operation,long now)throws SQLException {
        GmailCredentialStore.checkOwner(c,owner,true);Intent item=read(c,owner);
        if(item==null||!item.operation().equals(operation)||!item.status().equals("AUTHORIZING"))throw new SQLException("Authorization is no longer available","40001");
        state(c,owner,item,"EXCHANGING",now);
    }
    /** Returns false if an exchange may still have produced a Google grant. */
    static boolean cancel(Connection c,GmailCredentialStore.Owner owner,long now)throws SQLException {
        GmailCredentialStore.checkOwner(c,owner,true);Intent item=read(c,owner);if(item==null)return true;
        if(item.status().equals("AUTHORIZING")){
            try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_gmail_authorizations WHERE user_id=? AND owner_generation=? AND operation_id=? AND status='AUTHORIZING'")){
                q.setLong(1,owner.userId());q.setString(2,owner.generation());q.setString(3,item.operation());if(q.executeUpdate()!=1)throw new SQLException("Authorization changed","40001");}return true;
        }
        if(item.status().equals("EXCHANGING"))state(c,owner,item,"CANCEL_PENDING",now);
        return false;
    }
    static void unknown(Connection c,GmailCredentialStore.Owner owner,String operation,long now)throws SQLException {
        GmailCredentialStore.checkOwner(c,owner,true);Intent item=read(c,owner);
        if(item!=null&&item.operation().equals(operation)&&Set.of("EXCHANGING","CANCEL_PENDING").contains(item.status()))state(c,owner,item,"UNKNOWN",now);
    }
    /** The owner explicitly confirms revocation on Google's account page. */
    static void acknowledgeExternalRevocation(Connection c,GmailCredentialStore.Owner owner)throws SQLException {
        GmailCredentialStore.checkOwner(c,owner,true);Intent item=read(c,owner);
        if(item==null||!item.status().equals("UNKNOWN")||GmailCredentialStore.read(c,owner)!=null)
            throw new SQLException("Google authorization outcome is not ready for confirmation","40001");
        try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_gmail_authorizations WHERE user_id=? AND owner_generation=? AND operation_id=? AND status='UNKNOWN'")){
            q.setLong(1,owner.userId());q.setString(2,owner.generation());q.setString(3,item.operation());if(q.executeUpdate()!=1)throw new SQLException("Authorization changed","40001");}
    }
    /** Idempotent receipt. Late tokens after cancellation are stored disabled. */
    static String accept(Connection c,GmailCredentialStore.Owner owner,String operation,String token,byte[] vault,long now)throws SQLException,GeneralSecurityException {
        GmailCredentialStore.checkOwner(c,owner,true);Intent item=read(c,owner);
        if(item==null||!item.operation().equals(operation))throw new SQLException("Authorization intent unavailable","40001");
        if(Set.of("CONNECTED","REVOKING").contains(item.status())){
            var stored=GmailCredentialStore.read(c,owner);if(stored==null||!stored.status().equals(item.status()))throw new SQLException("Gmail receipt unavailable","40001");return item.status();}
        if(!Set.of("EXCHANGING","CANCEL_PENDING","UNKNOWN").contains(item.status()))throw new SQLException("Authorization was not exchanged","40001");
        String result=item.status().equals("EXCHANGING")?"CONNECTED":"REVOKING";
        GmailCredentialStore.save(c,owner,token,vault,now,result);state(c,owner,item,result,now);return result;
    }
    static void forgetRevoked(Connection c,GmailCredentialStore.Owner owner)throws SQLException {
        GmailCredentialStore.checkOwner(c,owner,true);Intent item=read(c,owner);
        if(item==null)return;
        if(!Set.of("CONNECTED","REVOKING").contains(item.status())||GmailCredentialStore.read(c,owner)!=null)throw new SQLException("Cannot remove authorization receipt","40001");
        try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_gmail_authorizations WHERE user_id=? AND owner_generation=? AND operation_id=?")){
            q.setLong(1,owner.userId());q.setString(2,owner.generation());q.setString(3,item.operation());if(q.executeUpdate()!=1)throw new SQLException("Authorization changed","40001");}
    }
    private GmailAuthorizationIntents(){}
}
