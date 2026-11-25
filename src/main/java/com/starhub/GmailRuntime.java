package com.starhub;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

/** Production adapter. No token is exposed through the account API. */
final class GmailRuntime implements GmailServlet.Backend {
    @Override public GmailCredentialStore.Owner signedIn(HttpSession session) throws Exception {
        if(session==null||!AccountServlet.activeSession(session))return null;
        FileStorage storage=FileStorage.get();storage.ensureReady();
        AccountServlet.providerIdle(storage,session,true);
        Object id=session.getAttribute("starhub.account.id"),generation=session.getAttribute("starhub.account.generation");
        if(!(id instanceof Long)||!(generation instanceof String))return null;
        return new GmailCredentialStore.Owner((Long)id,(String)generation);
    }
    @Override public boolean accountMatches(GmailCredentialStore.Owner owner,String expectedEmail) throws Exception {
        var store=atomic();return store.database.run(UUID.randomUUID().toString(),c->{
            try(var q=c.prepareStatement("SELECT email FROM users WHERE id=? AND generation=? AND active=1")){
                q.setLong(1,owner.userId());q.setString(2,owner.generation());
                try(var r=q.executeQuery()){return r.next()&&expectedEmail.equals(r.getString(1));}
            }
        });
    }
    @Override public GmailSecurity.Config config() throws IOException {
        Properties p=AccountServlet.config();
        try{return new GmailSecurity.Config(p.getProperty("gmail.oauth.client.id"),p.getProperty("gmail.oauth.client.secret"),
                java.net.URI.create(p.getProperty("gmail.oauth.redirect.uri","")));}
        catch(IllegalArgumentException invalid){throw new IOException("Gmail is not configured");}
    }
    @Override public byte[] vault() throws IOException {
        try{byte[] value=Base64.getDecoder().decode(AccountServlet.config().getProperty("vault.key",""));
            if(!java.util.Set.of(16,24,32).contains(value.length))throw new IllegalArgumentException();return value;}
        catch(IllegalArgumentException invalid){throw new IOException("Gmail is not configured");}
    }
    private AtomicFileStorage atomic() throws Exception {
        FileStorage storage=FileStorage.get();storage.ensureReady();if(storage.atomic==null)throw StorageFailure.unavailable();return storage.atomic;
    }
    @Override public GmailCredentialStore.Stored read(GmailCredentialStore.Owner owner) throws Exception {
        var store=atomic();return store.database.run(UUID.randomUUID().toString(),c->GmailCredentialStore.read(c,owner));
    }
    @Override public GmailAuthorizationIntents.Intent intent(GmailCredentialStore.Owner owner) throws Exception {
        var store=atomic();return store.database.run(UUID.randomUUID().toString(),c->GmailAuthorizationIntents.read(c,owner));
    }
    @Override public void begin(GmailCredentialStore.Owner owner,String operation,long now) throws Exception {
        var store=atomic();store.database.run(operation,c->{store.transientCapacity(c,32_768);GmailAuthorizationIntents.begin(c,owner,operation,now);return null;});
    }
    @Override public void exchange(GmailCredentialStore.Owner owner,String operation,long now) throws Exception {
        var store=atomic();store.database.run(operation,c->{store.transientCapacity(c,32_768);GmailAuthorizationIntents.exchange(c,owner,operation,now);return null;});
    }
    @Override public void unknown(GmailCredentialStore.Owner owner,String operation,long now) throws Exception {
        var store=atomic();store.database.run(operation,c->{store.transientCapacity(c,32_768);GmailAuthorizationIntents.unknown(c,owner,operation,now);return null;});
    }
    @Override public String accept(GmailCredentialStore.Owner owner,String operation,String token,byte[] vault,long now) throws Exception {
        var store=atomic();return store.database.run(operation,c->{store.transientCapacity(c,32_768);return GmailAuthorizationIntents.accept(c,owner,operation,token,vault,now);});
    }
    @Override public boolean cancel(GmailCredentialStore.Owner owner,long now) throws Exception {
        var store=atomic();return store.database.run(UUID.randomUUID().toString(),c->{store.transientCapacity(c,32_768);return GmailAuthorizationIntents.cancel(c,owner,now);});
    }
    @Override public void acknowledgeExternalRevocation(GmailCredentialStore.Owner owner) throws Exception {
        var store=atomic();store.database.run(UUID.randomUUID().toString(),c->{store.transientCapacity(c,32_768);GmailAuthorizationIntents.acknowledgeExternalRevocation(c,owner);return null;});
    }
    @Override public GmailCredentialStore.Stored beginDisconnect(GmailCredentialStore.Owner owner,long now) throws Exception {
        var store=atomic();return store.database.run(UUID.randomUUID().toString(),c->{store.transientCapacity(c,32_768);return GmailCredentialStore.beginDisconnect(c,owner,now);});
    }
    @Override public boolean finishDisconnect(GmailCredentialStore.Owner owner,GmailSecurity.Sealed expected) throws Exception {
        var store=atomic();return store.database.run(UUID.randomUUID().toString(),c->{store.transientCapacity(c,32_768);boolean removed=GmailCredentialStore.finishDisconnect(c,owner,expected);if(removed)GmailAuthorizationIntents.forgetRevoked(c,owner);return removed;});
    }
}
