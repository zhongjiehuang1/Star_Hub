package com.starhub;

import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class GmailCredentialStoreTest {
    private static final String GEN="ed8c456d-8f25-4a49-9f0f-ef39dc75323f";
    private static final String OTHER_GEN="0d8c456d-8f25-4a49-9f0f-ef39dc75323f";
    private static final byte[] VAULT=new byte[32];
    private Connection database() throws SQLException {
        Connection c=DriverManager.getConnection("jdbc:h2:mem:gmail_"+java.util.UUID.randomUUID()+";MODE=MySQL");
        try(Statement s=c.createStatement()) {
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,generation CHAR(36) NOT NULL,active BOOLEAN NOT NULL)");
            s.execute("CREATE TABLE starhub_gmail_credentials(user_id BIGINT NOT NULL,owner_generation CHAR(36) NOT NULL,status VARCHAR(16) NOT NULL,nonce BINARY(12) NOT NULL,ciphertext VARBINARY(8192) NOT NULL,updated_millis BIGINT NOT NULL,PRIMARY KEY(user_id,owner_generation),FOREIGN KEY(user_id) REFERENCES users(id))");
            s.execute("INSERT INTO users VALUES(42,'"+GEN+"',TRUE)");
        }
        c.setAutoCommit(false);return c;
    }

    @Test void onlyOwnerCanReadAnEncryptedCommittedToken() throws Exception {
        try(Connection c=database()) {
            var owner=new GmailCredentialStore.Owner(42,GEN);
            GmailCredentialStore.saveConnected(c,owner,"refresh-example",VAULT,100);c.commit();
            var row=GmailCredentialStore.read(c,owner);assertNotNull(row);assertEquals("CONNECTED",row.status());
            assertFalse(row.toString().contains("refresh-example"));
            assertNotEquals("refresh-example",new String(row.credential().ciphertext(),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("refresh-example",GmailCredentialStore.connectedToken(c,owner,VAULT));
            assertThrows(SQLException.class,()->GmailCredentialStore.saveConnected(c,owner,"other-token",VAULT,101));
            assertEquals("refresh-example",GmailCredentialStore.connectedToken(c,owner,VAULT));
            assertThrows(SQLException.class,()->GmailCredentialStore.read(c,new GmailCredentialStore.Owner(43,GEN)));
            assertThrows(SQLException.class,()->GmailCredentialStore.read(c,new GmailCredentialStore.Owner(42,OTHER_GEN)));
        }
    }

    @Test void failedTransactionDoesNotPersistCredential() throws Exception {
        try(Connection c=database()) {
            var owner=new GmailCredentialStore.Owner(42,GEN);c.commit();
            GmailCredentialStore.saveConnected(c,owner,"refresh-example",VAULT,100);c.rollback();
            assertNull(GmailCredentialStore.read(c,owner));
        }
    }

    @Test void disconnectDisablesReadsAndOnlyDeletesTheSameConfirmedCredential() throws Exception {
        try(Connection c=database()) {
            var owner=new GmailCredentialStore.Owner(42,GEN);
            GmailCredentialStore.saveConnected(c,owner,"refresh-example",VAULT,100);c.commit();
            var revoking=GmailCredentialStore.beginDisconnect(c,owner,101);c.commit();
            assertEquals("REVOKING",revoking.status());assertNull(GmailCredentialStore.connectedToken(c,owner,VAULT));
            assertThrows(SQLException.class,()->GmailCredentialStore.saveConnected(c,owner,"another-token",VAULT,102));
            var different=GmailSecurity.seal("other-token",VAULT,42,GEN,"refresh-token");
            assertFalse(GmailCredentialStore.finishDisconnect(c,owner,different));
            assertNotNull(GmailCredentialStore.read(c,owner));
            assertTrue(GmailCredentialStore.finishDisconnect(c,owner,revoking.credential()));c.commit();
            assertNull(GmailCredentialStore.read(c,owner));
        }
    }

    @Test void disabledOrChangedAccountCannotUseStoredCredential() throws Exception {
        try(Connection c=database()) {
            var owner=new GmailCredentialStore.Owner(42,GEN);
            GmailCredentialStore.saveConnected(c,owner,"refresh-example",VAULT,100);c.commit();
            try(PreparedStatement q=c.prepareStatement("UPDATE users SET active=FALSE WHERE id=42")){q.executeUpdate();}c.commit();
            assertThrows(SQLException.class,()->GmailCredentialStore.connectedToken(c,owner,VAULT));
            assertThrows(SQLException.class,()->GmailCredentialStore.beginDisconnect(c,owner,101));
        }
    }
}
