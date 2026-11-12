package com.starhub;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReplicaAuthorityTest {
    private static Properties endpoints(){
        Properties p=new Properties();p.setProperty("storage.replicated","true");
        p.setProperty("db.url","jdbc:mysql://127.0.0.1:3306/accounts?x=1");
        p.setProperty("storage.node2.db.url","jdbc:mysql://localhost:3306/accounts_peer?x=1");
        p.setProperty("storage.objectStore.jdbc.url","jdbc:mysql://127.0.0.1:3306/decisions?x=1");return p;
    }
    @Test void replicatedEndpointsMustShareOneLocalServer(){
        Properties p=endpoints();ReplicaAuthority.validateEndpoints(p);
        for(String value:new String[]{"jdbc:mysql://127.0.0.1:3307/accounts_peer?x=1", "jdbc:mysql://127.0.0.1:3306/accounts?x=1", "jdbc:mysql://example.invalid:3306/accounts_peer?x=1"}) {
            Properties changed=endpoints();changed.setProperty("storage.node2.db.url",value);
            assertThrows(IllegalArgumentException.class,()->ReplicaAuthority.validateEndpoints(changed));
        }
        Properties changed=endpoints();changed.setProperty("storage.objectStore.jdbc.url","jdbc:mysql://127.0.0.1:3307/decisions?x=1");
        assertThrows(IllegalArgumentException.class,()->ReplicaAuthority.validateEndpoints(changed));
    }
    @Test void privateAccountConfigurationCanBeReadFromAnExplicitPath() throws Exception {
        var path=java.nio.file.Files.createTempFile("starhub-config-test-", ".properties");
        String prior=System.getProperty("starhub.account.config");
        try{
            java.nio.file.Files.writeString(path,"server.id=2\n");
            System.setProperty("starhub.account.config",path.toString());
            assertEquals("2",AccountServlet.config().getProperty("server.id"));
        }finally{
            if(prior==null)System.clearProperty("starhub.account.config");else System.setProperty("starhub.account.config",prior);
            java.nio.file.Files.deleteIfExists(path);
        }
    }
    @Test void databaseMustMatchAuthenticatedObserverAndConfigurationCannotDrift() throws Exception {
        String uuid="11111111-1111-4111-8111-111111111111",token="test-only-replication-authority-token-32-plus";
        AtomicInteger code=new AtomicInteger(204);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/authority",exchange->{
            assertEquals("Bearer "+token,exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("X-Four-Project-MySQL-UUID",uuid);
            exchange.sendResponseHeaders(code.get(),-1);exchange.close();
        });server.start();
        try{
            Properties p=endpoints();p.setProperty("storage.probe.token",token);
            p.setProperty("storage.authority.url","http://127.0.0.1:"+server.getAddress().getPort()+"/authority");
            ReplicaAuthority.verify(connection(uuid),p);
            assertThrows(SQLException.class,()->ReplicaAuthority.verify(connection("22222222-2222-4222-8222-222222222222"),p));
            assertThrows(SQLException.class,()->ReplicaAuthority.verify(connection(uuid,0,"AFTER"),p));
            assertThrows(SQLException.class,()->ReplicaAuthority.verify(connection(uuid,1,"EVENTUAL"),p));
            code.set(503);assertThrows(SQLException.class,()->ReplicaAuthority.verify(connection(uuid),p));
            Properties changed=new Properties();changed.putAll(p);changed.setProperty("db.url","jdbc:mysql://127.0.0.1:3306/other?x=1");
            assertThrows(SQLException.class,()->ReplicaAuthority.sameRouting(p,changed));
        } finally{server.stop(0);}
        ReplicaAuthority.verify(connection("invalid"),new Properties()); // Legacy mode does not consult the observer.
    }
    @Test void atomicAuthorityRequiresTheFullStablePermitAndExplicitDurabilityConfirmation()throws Exception {
        String uuid=java.util.UUID.randomUUID().toString(),boot=java.util.UUID.randomUUID().toString(),token=java.util.UUID.randomUUID().toString();
        var complete=new AtomicBoolean(false);var epoch=new java.util.concurrent.atomic.AtomicLong(1);var durability=new java.util.concurrent.atomic.AtomicReference<>("dual");var confirmations=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/authority",exchange->{
            assertEquals("Bearer "+token,exchange.getRequestHeaders().getFirst("Authorization"));
            var h=exchange.getResponseHeaders();h.set("X-Four-Project-MySQL-UUID",uuid);h.set("X-Four-Project-Authority-Stamp","a".repeat(64));
            if(complete.get()){
                h.set("X-Four-Project-Permit","a".repeat(64));h.set("X-Four-Project-View-ID","view-1");h.set("X-Four-Project-Epoch",Long.toString(epoch.get()));
                h.set("X-Four-Project-Mode","dual");h.set("X-Four-Project-Controller-Boot-ID",boot);h.set("X-Four-Project-Membership","b".repeat(64));
            }
            exchange.sendResponseHeaders(204,-1);exchange.close();
        });
        server.createContext("/confirm",exchange->{
            assertEquals("POST",exchange.getRequestMethod());assertEquals("Bearer "+token,exchange.getRequestHeaders().getFirst("Authorization"));
            var body=ModelGateway.JSON.readTree(exchange.getRequestBody());assertEquals("a".repeat(64),body.path("permit").asText());assertEquals("synthetic-gtid:1-9",body.path("gtidSet").asText());
            assertTrue(body.path("operationId").asText().matches("[0-9a-f-]{36}"));confirmations.incrementAndGet();headers(exchange.getResponseHeaders(),new ReplicaAuthority.Lease(uuid,"a".repeat(64),"view-1",Long.toString(epoch.get()),"dual",boot,"b".repeat(64)));exchange.getResponseHeaders().set("X-Four-Project-Durability",durability.get());exchange.sendResponseHeaders(204,-1);exchange.close();
        });server.start();
        try{
            Properties p=endpoints();p.setProperty("storage.transactionMode","atomic");p.setProperty("storage.probe.token",token);p.setProperty("storage.authority.url","http://127.0.0.1:"+server.getAddress().getPort()+"/authority");
            assertThrows(SQLException.class,()->ReplicaAuthority.lease(p)); // A legacy stamp is not a new permit.
            complete.set(true);var lease=ReplicaAuthority.lease(p);ReplicaAuthority.unchanged(p,lease);
            assertThrows(SQLException.class,()->ReplicaAuthority.verify(connection(uuid,1,"AFTER"),p));ReplicaAuthority.verify(connection(uuid,1,"BEFORE_AND_AFTER"),p);
            ReplicaAuthority.confirm(p,lease,"synthetic-gtid:1-9",java.util.UUID.randomUUID().toString());assertEquals(1,confirmations.get());
            durability.set("single");assertThrows(SQLException.class,()->ReplicaAuthority.confirm(p,lease,"synthetic-gtid:1-9",java.util.UUID.randomUUID().toString()));
            epoch.incrementAndGet();assertThrows(SQLException.class,()->ReplicaAuthority.unchanged(p,lease));
        }finally{server.stop(0);}
    }
    private static java.util.Map<String,String> values(ReplicaAuthority.Lease lease){return java.util.Map.of(
            "MySQL-UUID",lease.mysqlUuid(),"Permit",lease.stamp(),"View-ID",lease.view(),"Epoch",lease.epoch(),"Mode",lease.mode(),"Controller-Boot-ID",lease.boot(),"Membership",lease.membership());}
    private static void headers(com.sun.net.httpserver.Headers headers,ReplicaAuthority.Lease lease){values(lease).forEach((name,value)->headers.set("X-Four-Project-"+name,value));headers.set("X-Four-Project-Durability",lease.mode());}
    private static ReplicaAuthority.Lease syntheticLease(){return new ReplicaAuthority.Lease(java.util.UUID.randomUUID().toString(),"c".repeat(64),"synthetic-view","3","dual",java.util.UUID.randomUUID().toString(),"d".repeat(64));}
    @Test void confirmationRejectsEveryMissingOrChangedLeaseHeader()throws Exception {
        var lease=syntheticLease();var changed=new java.util.concurrent.atomic.AtomicReference<String>();var missing=new AtomicBoolean();var count=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/confirm",exchange->{exchange.getRequestBody().readAllBytes();count.incrementAndGet();var h=exchange.getResponseHeaders();headers(h,lease);if(changed.get()!=null){String name="X-Four-Project-"+changed.get();if(missing.get())h.remove(name);else h.set(name,"different-grant");}exchange.sendResponseHeaders(204,-1);exchange.close();});server.start();
        try{
            Properties p=endpoints();p.setProperty("storage.probe.token","synthetic-confirmation-token-not-a-secret");p.setProperty("storage.authority.url","http://127.0.0.1:"+server.getAddress().getPort()+"/authority");
            assertThrows(SQLException.class,()->ReplicaAuthority.confirm(p,new ReplicaAuthority.Lease(lease.mysqlUuid(),null,null,null,"dual",null,null),"synthetic:1-9",java.util.UUID.randomUUID().toString()));assertEquals(0,count.get());
            ReplicaAuthority.confirm(p,lease,"synthetic:1-9",java.util.UUID.randomUUID().toString());
            for(String field:values(lease).keySet())for(boolean absent:new boolean[]{false,true}){changed.set(field);missing.set(absent);assertThrows(SQLException.class,()->ReplicaAuthority.confirm(p,lease,"synthetic:1-9",java.util.UUID.randomUUID().toString()),field+" must be present and exact");}
            assertEquals(15,count.get());
        }finally{server.stop(0);}
    }
    @Test void localAuthorityAndConfirmationIgnoreDefaultProxiesAndNeverFollowRedirects()throws Exception {
        var lease=syntheticLease();var redirect=new AtomicBoolean();var selected=new AtomicInteger();var traps=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        for(String path:java.util.List.of("/authority","/confirm"))server.createContext(path,exchange->{exchange.getRequestBody().readAllBytes();if(redirect.get()){exchange.getResponseHeaders().set("Location","/trap");exchange.sendResponseHeaders(302,-1);}else{headers(exchange.getResponseHeaders(),lease);exchange.sendResponseHeaders(204,-1);}exchange.close();});
        server.createContext("/trap",exchange->{traps.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});server.start();
        java.net.ProxySelector previous=java.net.ProxySelector.getDefault();
        try{
            java.net.ProxySelector.setDefault(new java.net.ProxySelector(){public java.util.List<java.net.Proxy> select(java.net.URI uri){selected.incrementAndGet();return java.util.List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",9)));}public void connectFailed(java.net.URI uri,java.net.SocketAddress address,java.io.IOException failure){}});
            Properties p=endpoints();p.setProperty("storage.probe.token","synthetic-proxy-test-token-not-a-secret");p.setProperty("storage.authority.url","http://127.0.0.1:"+server.getAddress().getPort()+"/authority");
            assertEquals(lease,ReplicaAuthority.lease(p));ReplicaAuthority.confirm(p,lease,"synthetic:1-9",java.util.UUID.randomUUID().toString());assertEquals(0,selected.get());
            redirect.set(true);assertThrows(SQLException.class,()->ReplicaAuthority.lease(p));assertThrows(SQLException.class,()->ReplicaAuthority.confirm(p,lease,"synthetic:1-9",java.util.UUID.randomUUID().toString()));assertEquals(0,traps.get());assertEquals(0,selected.get());
        }finally{java.net.ProxySelector.setDefault(previous);server.stop(0);}
    }
    @Test void neitherAuthorityNorConfirmationSendsCredentialsToAnInvalidObserverAddress()throws Exception {
        var lease=syntheticLease();var received=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{received.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});server.start();
        try{
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            for(String address:java.util.List.of(base+"/%61uthority",base+"/authority?x=1",base+"/authority#fragment",base+"/other",base.replace("http://","http://synthetic@")+"/authority",base.replace("127.0.0.1","example.invalid")+"/authority",base.replace("http:","https:")+"/authority")){
                Properties p=endpoints();p.setProperty("storage.probe.token","synthetic-invalid-url-test-token-not-a-secret");p.setProperty("storage.authority.url",address);
                assertThrows(SQLException.class,()->ReplicaAuthority.lease(p));assertThrows(SQLException.class,()->ReplicaAuthority.confirm(p,lease,"synthetic:1-9",java.util.UUID.randomUUID().toString()));
            }
            assertEquals(0,received.get());
        }finally{server.stop(0);}
    }
    private static Connection connection(String identity) {return connection(identity,1,"AFTER");}
    private static Connection connection(String identity,int binlog,String consistency) {
        AtomicBoolean first=new AtomicBoolean(true);
        ResultSet rows=(ResultSet)Proxy.newProxyInstance(ReplicaAuthorityTest.class.getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()){
            case "next"->first.getAndSet(false);case "getString"->((Integer)a[0])==1?identity:consistency;case "getInt"->binlog;default->null;
        });
        Statement statement=(Statement)Proxy.newProxyInstance(ReplicaAuthorityTest.class.getClassLoader(),new Class[]{Statement.class},(p,m,a)->m.getName().equals("executeQuery")?rows:null);
        return (Connection)Proxy.newProxyInstance(ReplicaAuthorityTest.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->m.getName().equals("createStatement")?statement:null);
    }
}
