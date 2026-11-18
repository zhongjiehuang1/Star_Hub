package com.starhub;

import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Account-owned, encrypted Gmail refresh credentials.
 *
 * Each method must be invoked inside the application's certified atomic
 * request/transaction scope. Callers must not return Stored or its ciphertext
 * to a browser, and must confirm the storage operation before claiming success.
 */
final class GmailCredentialStore {
    record Owner(long userId, String generation) {
        Owner {
            if (userId < 1 || generation == null || !generation.matches("[0-9a-fA-F-]{36}"))
                throw new IllegalArgumentException("Invalid Gmail credential owner");
        }
    }
    record Stored(String status, GmailSecurity.Sealed credential) {
        Stored {
            if (!Objects.equals(status,"CONNECTED") && !Objects.equals(status,"REVOKING"))
                throw new IllegalArgumentException("Invalid Gmail credential state");
            Objects.requireNonNull(credential);
        }
        @Override public String toString() { return "GmailCredential[redacted]"; }
    }

    static void checkOwner(Connection c, Owner owner, boolean lock) throws SQLException {
        String sql="SELECT generation,active FROM users WHERE id=?"+(lock?" FOR UPDATE":"");
        try(PreparedStatement q=c.prepareStatement(sql)) {
            q.setLong(1,owner.userId());
            try(ResultSet r=q.executeQuery()) {
                if(!r.next() || !r.getBoolean(2) || !owner.generation().equals(r.getString(1)))
                    throw new SQLException("Gmail account is unavailable", "28000");
            }
        }
    }

    static Stored read(Connection c, Owner owner) throws SQLException {
        checkOwner(c,owner,false);
        try(PreparedStatement q=c.prepareStatement("SELECT status,nonce,ciphertext FROM starhub_gmail_credentials WHERE user_id=? AND owner_generation=?")) {
            q.setLong(1,owner.userId()); q.setString(2,owner.generation());
            try(ResultSet r=q.executeQuery()) {
                return r.next() ? new Stored(r.getString(1),new GmailSecurity.Sealed(r.getBytes(2),r.getBytes(3))) : null;
            }
        }
    }

    /** A disconnected or revocation-pending account can never authorize reads. */
    static String connectedToken(Connection c, Owner owner, byte[] vault) throws SQLException, GeneralSecurityException {
        Stored value=read(c,owner);
        if(value==null || !value.status().equals("CONNECTED"))return null;
        return GmailSecurity.open(value.credential(),vault,owner.userId(),owner.generation(),"refresh-token");
    }

    /** Saves only an OAuth token received after a verified user authorization. */
    static void saveConnected(Connection c, Owner owner, String refreshToken, byte[] vault, long now)
            throws SQLException, GeneralSecurityException {
        save(c,owner,refreshToken,vault,now,"CONNECTED");
    }

    static void save(Connection c, Owner owner, String refreshToken, byte[] vault, long now,String status)
            throws SQLException, GeneralSecurityException {
        if(!"CONNECTED".equals(status)&&!"REVOKING".equals(status))throw new IllegalArgumentException("Invalid Gmail status");
        if(now<0)throw new IllegalArgumentException("Invalid Gmail timestamp");
        checkOwner(c,owner,true);
        Stored existing=read(c,owner);
        if(existing!=null)
            throw new SQLException("Disconnect the existing Gmail connection first", "40001");
        GmailSecurity.Sealed sealed=GmailSecurity.seal(refreshToken,vault,owner.userId(),owner.generation(),"refresh-token");
        try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_gmail_credentials(user_id,owner_generation,status,nonce,ciphertext,updated_millis) VALUES(?,?,?,?,?,?)")) {
            q.setLong(1,owner.userId());q.setString(2,owner.generation());q.setString(3,status);
            q.setBytes(4,sealed.nonce());q.setBytes(5,sealed.ciphertext());q.setLong(6,now);q.executeUpdate();
        }
    }

    /** Locally disables mailbox reads before any upstream revocation attempt. */
    static Stored beginDisconnect(Connection c, Owner owner, long now) throws SQLException {
        if(now<0)throw new IllegalArgumentException("Invalid Gmail timestamp");
        checkOwner(c,owner,true);Stored value=read(c,owner);if(value==null)return null;
        if(value.status().equals("CONNECTED")) {
            try(PreparedStatement q=c.prepareStatement("UPDATE starhub_gmail_credentials SET status=?,updated_millis=? WHERE user_id=? AND owner_generation=? AND status=?")) {
                q.setString(1,"REVOKING");q.setLong(2,now);q.setLong(3,owner.userId());q.setString(4,owner.generation());q.setString(5,"CONNECTED");
                if(q.executeUpdate()!=1)throw new SQLException("Gmail credential changed", "40001");
            }
        }
        return new Stored("REVOKING",value.credential());
    }

    /** Delete only the exact disabled credential whose upstream revoke was confirmed. */
    static boolean finishDisconnect(Connection c, Owner owner, GmailSecurity.Sealed expected) throws SQLException {
        checkOwner(c,owner,true);Objects.requireNonNull(expected);
        try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_gmail_credentials WHERE user_id=? AND owner_generation=? AND status=? AND nonce=? AND ciphertext=?")) {
            q.setLong(1,owner.userId());q.setString(2,owner.generation());q.setString(3,"REVOKING");
            q.setBytes(4,expected.nonce());q.setBytes(5,expected.ciphertext());return q.executeUpdate()==1;
        }
    }

    private GmailCredentialStore() { }
}
