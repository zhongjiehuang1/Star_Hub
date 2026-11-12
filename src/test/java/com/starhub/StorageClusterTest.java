package com.starhub;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageClusterTest {
    @Test void replicatedJdbcCannotEnableReconnectOrMultiStatementAndAlwaysUsesUtc()throws Exception {
        var properties=new java.util.Properties();properties.setProperty("storage.replicated","true");properties.setProperty("db.url","jdbc:mysql://127.0.0.1:331/starhub_accounts?sslMode=VERIFY_IDENTITY&autoReconnect=true&allowMultiQueries=true&socketTimeout=1&useServerPrepStmts=false&cachePrepStmts=true&serverTimezone=Asia%2FShanghai");
        String url=AccountServlet.jdbcUrl(properties,"db.url");assertTrue(url.contains("sslMode=VERIFY_IDENTITY"));assertTrue(url.contains("autoReconnect=false"));assertTrue(url.contains("allowMultiQueries=false"));assertTrue(url.contains("socketTimeout=90000"));assertTrue(url.contains("connectionTimeZone=UTC"));assertTrue(url.contains("forceConnectionTimeZoneToSession=true"));assertFalse(url.contains("autoReconnect=true"));assertFalse(url.contains("Asia"));
        assertTrue(url.contains("useServerPrepStmts=true"));assertTrue(url.contains("cachePrepStmts=false"));assertFalse(url.contains("useServerPrepStmts=false"));assertFalse(url.contains("cachePrepStmts=true"));
    }
    @Test void replicatedDatabaseWaitRemainsBoundedAndLegacyWaitIsUnchanged(){
        var properties=new java.util.Properties();
        assertEquals(15_000,AccountServlet.databaseSocketTimeout(properties));
        properties.setProperty("storage.replicated","true");
        assertEquals(90_000,AccountServlet.databaseSocketTimeout(properties));
    }
    private com.fasterxml.jackson.databind.node.ObjectNode probe(long free){
        var value=ModelGateway.JSON.createObjectNode().put("nodeId",1).put("observedAtMillis",100_000L)
                .put("mysqlReady",true).put("tomcatReady",true).put("reserveGuaranteed",true)
                .put("physicalBytesPerPayloadByte",2).put("fixedOverheadBytes",1024);
        value.putArray("volumes").addObject().put("id","database").put("availableBytes",free);return value;
    }
    @Test void postUploadReserveIncludesPhysicalAmplificationAndOutstandingReservations(){
        var candidate=StorageCluster.parse(probe(StorageCluster.FLOOR+4_024L),1,100_000);
        assertTrue(candidate.admits(1000,1000,100_000));
        assertFalse(candidate.admits(1001,1000,100_000));
        assertFalse(candidate.admits(1000,1001,100_000));
        assertFalse(StorageCluster.parse(probe(4_000_000_000L),1,100_000).admits(1,0,100_000));
        assertFalse(candidate.admits(1000,0,106_000));
    }
    @Test void unprotectedStaleOrUnhealthyProbesNeverAdmitWrites(){
        var value=probe(100_000_000_000L);value.put("reserveGuaranteed",false);
        assertFalse(StorageCluster.parse(value,1,100_000).admits(1,0,100_000));
        value.put("mysqlReady",false);assertThrows(StorageFailure.class,()->StorageCluster.parse(value,1,100_000));
        assertThrows(StorageFailure.class,()->StorageCluster.parse(probe(100_000_000_000L),1,106_000));
        assertThrows(StorageFailure.class,()->StorageCluster.parse(probe(100_000_000_000L),2,100_000));
    }
    @Test void userQuotaIncludesInFlightUploadsAndUsesOverflowSafeArithmetic(){
        assertTrue(FileStorage.fits(1_000_000_000L,0,1_000_000_000L));
        assertTrue(FileStorage.fits(1_999_999_998L,1,1));assertFalse(FileStorage.fits(1_999_999_998L,1,2));
        assertFalse(FileStorage.fits(2_000_000_000L,0,1));assertFalse(FileStorage.fits(1,Long.MAX_VALUE,1));
        assertFalse(FileStorage.fits(-1,0,1));
    }
    @Test void probeDeadlineCoversAStalledResponseBody()throws Exception{
        var release=new java.util.concurrent.CountDownLatch(1);
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/status",exchange->{
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200,10);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
            try{release.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally{exchange.close();}
        });server.start();
        var config=new java.util.Properties();config.setProperty("storage.probe.token","test-only");
        StorageCluster cluster=ProbeHttpFixture.cluster(config,server);
        try{assertTimeoutPreemptively(java.time.Duration.ofSeconds(6),()->assertThrows(StorageFailure.class,cluster::probes));assertEquals(1,requests.get());}
        finally{release.countDown();server.stop(0);}
    }
    @Test void replicatedModeRequiresAnAuthorizedLocalReplicationView()throws Exception{
        var mode=new java.util.concurrent.atomic.AtomicReference<>("");
        var authorized=new java.util.concurrent.atomic.AtomicBoolean(false);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/status",exchange->{
            int node=exchange.getRequestURI().getQuery().equals("node=1")?1:2;
            var body=probe(50_000_000_000L).put("nodeId",node).put("observedAtMillis",System.currentTimeMillis())
                    .put("replicationMode",mode.get()).put("singleNodeAuthorized",authorized.get());
            byte[] bytes=ModelGateway.JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try{
            var config=new java.util.Properties();config.setProperty("storage.replicated","true");config.setProperty("server.id","1");
            config.setProperty("storage.probe.token","test-only");
            StorageCluster cluster=ProbeHttpFixture.cluster(config,server);
            assertThrows(StorageFailure.class,cluster::probes);
            mode.set("dual");assertEquals(2,cluster.probes().size());
            mode.set("single");assertThrows(StorageFailure.class,cluster::probes);
            authorized.set(true);assertEquals(1,cluster.probes().size());
            mode.set("dual");assertThrows(StorageFailure.class,cluster::probes);
        }finally{server.stop(0);}
    }
    @Test void atomicStorageAcceptsHealthyPeerDatabaseWhenPeerTomcatIsDownButKeepsTheLocalApplicationGate()throws Exception {
        var peerMysql=new java.util.concurrent.atomic.AtomicBoolean(true);var localTomcat=new java.util.concurrent.atomic.AtomicBoolean(true);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/status",exchange->{
            int node=Integer.parseInt(exchange.getRequestURI().getQuery().substring(5));
            var body=probe(50_000_000_000L).put("nodeId",node).put("observedAtMillis",System.currentTimeMillis()).put("replicationMode","dual").put("singleNodeAuthorized",false)
                .put("viewId","synthetic-view").put("membershipDigest","a".repeat(64)).put("memberUuid","member-"+node).put("capacityModelValidated",true)
                .put("mysqlReady",node==1||peerMysql.get()).put("tomcatReady",node==1&&localTomcat.get());
            body.putArray("onlineNodeIds").add(1).add(2);body.putArray("configuredNodeIds").add(1).add(2);
            byte[] bytes=ModelGateway.JSON.writeValueAsBytes(body);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try{
            var config=new java.util.Properties();config.setProperty("storage.replicated","true");config.setProperty("storage.transactionMode","atomic");config.setProperty("server.id","1");config.setProperty("storage.physicalNodeIds","1,2");config.setProperty("storage.probe.token","test-only");
            StorageCluster cluster=ProbeHttpFixture.cluster(config,server);assertEquals(java.util.Set.of(1,2),cluster.probes().keySet());
            peerMysql.set(false);assertThrows(StorageFailure.class,cluster::probes);peerMysql.set(true);localTomcat.set(false);assertThrows(StorageFailure.class,cluster::probes);localTomcat.set(true);
            config.remove("storage.transactionMode");assertThrows(StorageFailure.class,cluster::probes);
        }finally{server.stop(0);}
    }
    @Test void physicalMembershipUsesTheAuthenticatedViewAndChecksEveryOnlineReplica()throws Exception{
        var mode=new java.util.concurrent.atomic.AtomicReference<>("dual");
        var members=new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of(1,2));
        var badPeer=new java.util.concurrent.atomic.AtomicBoolean(false);
        var oldMembership=new java.util.concurrent.atomic.AtomicBoolean(false);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/status",exchange->{
            int node=Integer.parseInt(exchange.getRequestURI().getQuery().substring(5));
            var body=probe(50_000_000_000L).put("nodeId",node).put("observedAtMillis",System.currentTimeMillis())
                    .put("replicationMode",mode.get()).put("singleNodeAuthorized",mode.get().equals("single"))
                    .put("viewId","view-1").put("membershipDigest","a".repeat(64)).put("memberUuid","member-"+node);
            if(node==2&&badPeer.get())body.put("mysqlReady",false);
            if(node==2&&oldMembership.get())body.put("membershipDigest","b".repeat(64));
            var ids=body.putArray("onlineNodeIds");members.get().forEach(ids::add);
            body.putArray("configuredNodeIds").add(1).add(2);
            byte[] bytes=ModelGateway.JSON.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try{
            var config=new java.util.Properties();config.setProperty("storage.replicated","true");config.setProperty("server.id","1");
            config.setProperty("storage.physicalNodeIds","1,2");config.setProperty("storage.probe.token","test-only");
            StorageCluster cluster=ProbeHttpFixture.cluster(config,server);
            assertEquals(java.util.Set.of(1,2),cluster.probes().keySet());
            badPeer.set(true);assertThrows(StorageFailure.class,cluster::probes);badPeer.set(false);
            oldMembership.set(true);assertThrows(StorageFailure.class,cluster::probes);oldMembership.set(false);
            members.set(java.util.List.of(1));assertThrows(StorageFailure.class,cluster::probes);
            mode.set("single");assertEquals(java.util.Set.of(1),cluster.probes().keySet());
            members.set(java.util.List.of(1,4));mode.set("quorum");assertThrows(StorageFailure.class,cluster::probes);
            config.setProperty("storage.physicalNodeIds","1,2,2");assertThrows(StorageFailure.class,cluster::probes);
        }finally{server.stop(0);}
    }
    @Test void everyProbeEndpointIsValidatedBeforeSendingAnyBearerIncludingSingleMode()throws Exception {
        for(String invalid:java.util.List.of("https://127.0.0.1:941/status","http://localhost:941/status","http://example.test:941/status","http://127.0.0.1:331/status","http://127.0.0.1:941/status?x=1","http://127.0.0.1:941/status?","http://127.0.0.1:941/status#fragment","http://127.0.0.1:941/status#","http://synthetic@127.0.0.1:941/status","http://127.0.0.1:941/%73tatus","http://127.0.0.1:941/status/","http://127.0.0.1:942/status")){
            var config=ProbeHttpFixture.endpoints(new java.util.Properties());config.setProperty("storage.probe.token","synthetic-private-probe-token-not-a-secret");config.setProperty("storage.node1.probe.url",invalid);
            var client=org.mockito.Mockito.mock(java.net.http.HttpClient.class);assertThrows(StorageFailure.class,()->new StorageCluster(config,client).probes());org.mockito.Mockito.verifyNoInteractions(client);
        }
        for(String invalid:java.util.List.of("duplicate","third","missing")){
            var config=ProbeHttpFixture.endpoints(new java.util.Properties());config.setProperty("storage.probe.token","synthetic-private-probe-token-not-a-secret");
            if(invalid.equals("duplicate"))config.setProperty("storage.node2.probe.url",config.getProperty("storage.node1.probe.url"));
            else if(invalid.equals("third"))config.setProperty("storage.node3.probe.url","http://127.0.0.1:943/status");else config.remove("storage.node2.probe.url");
            var client=org.mockito.Mockito.mock(java.net.http.HttpClient.class);assertThrows(StorageFailure.class,()->new StorageCluster(config,client).probes());org.mockito.Mockito.verifyNoInteractions(client);
        }
    }
    @Test void privateProbeClientIgnoresDefaultProxyAndDoesNotFollowRedirects()throws Exception {
        var selected=new java.util.concurrent.atomic.AtomicInteger();var traps=new java.util.concurrent.atomic.AtomicInteger();var received=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/status",exchange->{received.incrementAndGet();assertEquals("Bearer synthetic-private-probe-token-not-a-secret",exchange.getRequestHeaders().getFirst("Authorization"));exchange.getResponseHeaders().set("Location","/trap");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/trap",exchange->{traps.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});server.start();var prior=java.net.ProxySelector.getDefault();
        try{
            var actual=ProbeHttpFixture.actualDirectClient();assertTrue(actual.proxy().isPresent(),"An implicit captured default proxy is not an explicit direct transport");assertEquals(java.util.List.of(java.net.Proxy.NO_PROXY),actual.proxy().orElseThrow().select(java.net.URI.create("http://127.0.0.1:941/status")));
            java.net.ProxySelector.setDefault(new java.net.ProxySelector(){public java.util.List<java.net.Proxy> select(java.net.URI uri){selected.incrementAndGet();return java.util.List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP,new java.net.InetSocketAddress("127.0.0.1",9)));}public void connectFailed(java.net.URI uri,java.net.SocketAddress address,java.io.IOException failure){}});
            var config=new java.util.Properties();config.setProperty("storage.probe.token","synthetic-private-probe-token-not-a-secret");var cluster=ProbeHttpFixture.cluster(config,server);
            assertThrows(StorageFailure.class,cluster::probes);assertEquals(1,received.get());assertEquals(0,traps.get());assertEquals(0,selected.get());
        }finally{java.net.ProxySelector.setDefault(prior);server.stop(0);}
    }
}
