package com.starhub;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;

class GmailAuthorizationIntentsTest {
    private static final String GEN="ed8c456d-8f25-4a49-9f0f-ef39dc75323f";
    private static final byte[] KEY=new byte[32];
    private static final GmailCredentialStore.Owner OWNER=new GmailCredentialStore.Owner(42,GEN);
    private Connection db()throws SQLException{
        Connection c=DriverManager.getConnection("jdbc:h2:mem:intents_"+UUID.randomUUID()+";MODE=MySQL");
        try(Statement s=c.createStatement()){
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,generation CHAR(36) NOT NULL,active BOOLEAN NOT NULL)");
            s.execute("INSERT INTO users VALUES(42,'"+GEN+"',TRUE)");
            s.execute("CREATE TABLE starhub_gmail_credentials(user_id BIGINT NOT NULL,owner_generation CHAR(36) NOT NULL,status VARCHAR(16) NOT NULL,nonce BINARY(12) NOT NULL,ciphertext VARBINARY(8192) NOT NULL,updated_millis BIGINT NOT NULL,PRIMARY KEY(user_id,owner_generation))");
            s.execute("CREATE TABLE starhub_gmail_authorizations(user_id BIGINT NOT NULL,owner_generation CHAR(36) NOT NULL,operation_id CHAR(36) NOT NULL UNIQUE,status VARCHAR(24) NOT NULL,updated_millis BIGINT NOT NULL,PRIMARY KEY(user_id,owner_generation))");
        }c.setAutoCommit(false);return c;
    }
    @Test void retryOfPersistUsesSameReceiptAndDoesNotReplaceToken()throws Exception{
        try(Connection c=db()){
            String op=UUID.randomUUID().toString();GmailAuthorizationIntents.begin(c,OWNER,op,1);c.commit();GmailAuthorizationIntents.exchange(c,OWNER,op,2);c.commit();
            assertEquals("CONNECTED",GmailAuthorizationIntents.accept(c,OWNER,op,"first-token",KEY,3));c.commit();
            assertEquals("CONNECTED",GmailAuthorizationIntents.accept(c,OWNER,op,"second-token",KEY,4));
            assertEquals("first-token",GmailCredentialStore.connectedToken(c,OWNER,KEY));
            assertThrows(SQLException.class,()->GmailAuthorizationIntents.exchange(c,OWNER,op,5));
        }
    }
    @Test void disconnectDuringExchangeMakesLateTokenUnreadable()throws Exception{
        try(Connection c=db()){
            String op=UUID.randomUUID().toString();GmailAuthorizationIntents.begin(c,OWNER,op,1);GmailAuthorizationIntents.exchange(c,OWNER,op,2);c.commit();
            assertFalse(GmailAuthorizationIntents.cancel(c,OWNER,3));c.commit();
            assertEquals("REVOKING",GmailAuthorizationIntents.accept(c,OWNER,op,"late-token",KEY,4));c.commit();
            assertNull(GmailCredentialStore.connectedToken(c,OWNER,KEY));var pending=GmailCredentialStore.beginDisconnect(c,OWNER,5);
            assertTrue(GmailCredentialStore.finishDisconnect(c,OWNER,pending.credential()));GmailAuthorizationIntents.forgetRevoked(c,OWNER);c.commit();assertNull(GmailAuthorizationIntents.read(c,OWNER));
        }
    }
    @Test void unknownOutcomeRemainsBlockedAndLateTokenCannotEnableReads()throws Exception{
        try(Connection c=db()){
            String op=UUID.randomUUID().toString();GmailAuthorizationIntents.begin(c,OWNER,op,1);GmailAuthorizationIntents.exchange(c,OWNER,op,2);GmailAuthorizationIntents.unknown(c,OWNER,op,3);c.commit();
            assertEquals("UNKNOWN",GmailAuthorizationIntents.read(c,OWNER).status());assertFalse(GmailAuthorizationIntents.cancel(c,OWNER,4));
            assertThrows(SQLException.class,()->GmailAuthorizationIntents.begin(c,OWNER,UUID.randomUUID().toString(),5));
            assertEquals("REVOKING",GmailAuthorizationIntents.accept(c,OWNER,op,"late-token",KEY,6));assertNull(GmailCredentialStore.connectedToken(c,OWNER,KEY));
        }
    }
    @Test void cancelledUnstartedAuthorizationCannotExchange()throws Exception{
        try(Connection c=db()){
            String op=UUID.randomUUID().toString();GmailAuthorizationIntents.begin(c,OWNER,op,1);assertTrue(GmailAuthorizationIntents.cancel(c,OWNER,2));
            assertThrows(SQLException.class,()->GmailAuthorizationIntents.exchange(c,OWNER,op,3));assertNull(GmailAuthorizationIntents.read(c,OWNER));
        }
    }
    @Test void userMayAcknowledgeGoogleRevocationOnlyAfterKnownUnknownOutcome()throws Exception{
        try(Connection c=db()){
            String op=UUID.randomUUID().toString();GmailAuthorizationIntents.begin(c,OWNER,op,1);GmailAuthorizationIntents.exchange(c,OWNER,op,2);
            assertThrows(SQLException.class,()->GmailAuthorizationIntents.acknowledgeExternalRevocation(c,OWNER));
            GmailAuthorizationIntents.unknown(c,OWNER,op,3);GmailAuthorizationIntents.acknowledgeExternalRevocation(c,OWNER);
            assertNull(GmailAuthorizationIntents.read(c,OWNER));GmailAuthorizationIntents.begin(c,OWNER,UUID.randomUUID().toString(),4);
        }
    }
    @Test void simultaneousAcceptAndDisconnectNeverLeaveReadableCredential()throws Exception{
        try(Connection first=db()){
            String url=first.getMetaData().getURL(),op=UUID.randomUUID().toString();GmailAuthorizationIntents.begin(first,OWNER,op,1);GmailAuthorizationIntents.exchange(first,OWNER,op,2);first.commit();
            try(Connection second=DriverManager.getConnection(url)){
                second.setAutoCommit(false);ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
                try{
                    Future<?> accept=executor.submit(()->{try{start.await();GmailAuthorizationIntents.accept(first,OWNER,op,"late-token",KEY,3);first.commit();}catch(Exception e){throw new RuntimeException(e);}});
                    Future<?> cancel=executor.submit(()->{try{start.await();GmailAuthorizationIntents.cancel(second,OWNER,3);GmailCredentialStore.beginDisconnect(second,OWNER,3);second.commit();}catch(Exception e){throw new RuntimeException(e);}});
                    start.countDown();accept.get(10,TimeUnit.SECONDS);cancel.get(10,TimeUnit.SECONDS);
                    assertNull(GmailCredentialStore.connectedToken(first,OWNER,KEY));assertEquals("REVOKING",GmailCredentialStore.read(first,OWNER).status());
                }finally{executor.shutdownNow();}
            }
        }
    }
}
