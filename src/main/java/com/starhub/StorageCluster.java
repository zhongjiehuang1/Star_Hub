package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;

/** Authenticated capacity observations. A disk sample alone is never treated as an enforced reserve. */
final class StorageCluster {
    static final long FLOOR=4_000_000_000L, MAX_AGE=5_000L;
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress address,java.io.IOException failure){}})
            .followRedirects(HttpClient.Redirect.NEVER).build();
    final Properties config;
    private final HttpClient http;
    StorageCluster(Properties config) { this(config,HTTP); }
    StorageCluster(Properties config,HttpClient http) { this.config=config;this.http=Objects.requireNonNull(http); }

    private Map<Integer,URI> endpoints(){
        Map<Integer,URI> result=new LinkedHashMap<>();Set<URI> distinct=new HashSet<>();
        for(String key:config.stringPropertyNames())if(key.matches("storage\\.node[0-9]+\\.probe\\.url")&&!Set.of("storage.node1.probe.url","storage.node2.probe.url").contains(key))throw StorageFailure.unavailable();
        for(int node:List.of(1,2)){
            String value=required("storage.node"+node+".probe.url"),expected="http://127.0.0.1:"+(940+node)+"/status";
            if(!expected.equals(value))throw StorageFailure.unavailable();URI uri=URI.create(value);
            if(!distinct.add(uri))throw StorageFailure.unavailable();result.put(node,uri);
        }
        return Map.copyOf(result);
    }

    static final class Probe {
        final int node; final long observed,available,factor,fixed; final boolean protectedReserve,singleAuthorized;
        final String replicationMode;
        Probe(int node,long observed,long available,long factor,long fixed,boolean protectedReserve) {
            this(node,observed,available,factor,fixed,protectedReserve,false,"");
        }
        Probe(int node,long observed,long available,long factor,long fixed,boolean protectedReserve,boolean singleAuthorized) {
            this(node,observed,available,factor,fixed,protectedReserve,singleAuthorized,"");
        }
        Probe(int node,long observed,long available,long factor,long fixed,boolean protectedReserve,boolean singleAuthorized,String replicationMode) {
            this.node=node;this.observed=observed;this.available=available;this.factor=factor;this.fixed=fixed;this.protectedReserve=protectedReserve;
            this.singleAuthorized=singleAuthorized;this.replicationMode=replicationMode;
        }
        long budget(long payload) {
            if(payload<0||!protectedReserve) throw StorageFailure.unavailable();
            try{return Math.addExact(Math.multiplyExact(payload,factor),fixed);}
            catch(ArithmeticException invalid){throw StorageFailure.unavailable();}
        }
        boolean admits(long payload,long reserved,long now) {
            if(!fresh(observed,now)||reserved<0||!protectedReserve)return false;
            long budget=budget(payload);
            return available>=FLOOR && reserved<=available-FLOOR && budget<=available-FLOOR-reserved;
        }
    }

    static boolean fresh(long observed,long now) { return observed>0 && observed<=now+1_000L && now-observed<=MAX_AGE; }
    static Probe parse(JsonNode value,int node,long now) {return parse(value,node,now,true);}
    private static Probe parse(JsonNode value,int node,long now,boolean requireApplication) {
        if(value.path("nodeId").asInt(-1)!=node || !value.path("mysqlReady").asBoolean(false)
                || (requireApplication&&!value.path("tomcatReady").asBoolean(false)) || !value.path("observedAtMillis").canConvertToLong()
                || !fresh(value.path("observedAtMillis").longValue(),now))throw StorageFailure.unavailable();
        JsonNode volumes=value.path("volumes");
        if(!volumes.isArray()||volumes.isEmpty())throw StorageFailure.unavailable();
        long available=Long.MAX_VALUE; Set<String> identifiers=new HashSet<>();
        for(JsonNode volume:volumes){
            if(volume.path("id").asText().isBlank() || !identifiers.add(volume.path("id").asText())
                    || !volume.path("availableBytes").isIntegralNumber() || !volume.path("availableBytes").canConvertToLong()
                    || volume.path("availableBytes").longValue()<0)throw StorageFailure.unavailable();
            available=Math.min(available,volume.path("availableBytes").longValue());
        }
        long factor=value.path("physicalBytesPerPayloadByte").asLong(-1),fixed=value.path("fixedOverheadBytes").asLong(-1);
        if(factor<1||factor>1_000_000||fixed<0)throw StorageFailure.unavailable();
        return new Probe(node,value.path("observedAtMillis").longValue(),available,factor,fixed,value.path("reserveGuaranteed").asBoolean(false),value.path("singleNodeAuthorized").asBoolean(false),value.path("replicationMode").asText(""));
    }

    Map<Integer,Probe> probes() {
        try {if(SingleDatabase.enabled(config))return singleDatabaseProbe();}
        catch(java.sql.SQLException invalid){throw StorageFailure.unavailable();}
        Map<Integer,URI> endpoints=endpoints();
        String physical=config.getProperty("storage.physicalNodeIds","").trim();
        if(!physical.isEmpty())return physicalProbes(physical,endpoints);
        Map<Integer,Probe> result=new LinkedHashMap<>();
        boolean replicated="true".equals(config.getProperty("storage.replicated"));
        int local=0;
        if(replicated) {
            String id=required("server.id");
            if(!"1".equals(id)&&!"2".equals(id))throw StorageFailure.unavailable();
            local=Integer.parseInt(id);
        }
        int[] order=replicated?new int[]{local,3-local}:new int[]{1,2};
        for(int node:order) {
            try {
                result.put(node,parse(fetch(node,endpoints),node,System.currentTimeMillis()));
                // Only the authenticated local agent can report the writable
                // replication view or authorize a fenced single-node view.
                if(replicated&&node==local) {
                    Probe observed=result.get(node);
                    if("single".equals(observed.replicationMode)&&observed.singleAuthorized)break;
                    if(!"dual".equals(observed.replicationMode)||observed.singleAuthorized)throw StorageFailure.unavailable();
                }
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw StorageFailure.unavailable();}
            catch(Exception failure){throw StorageFailure.unavailable();}
        }
        return result;
    }

    Set<Integer> physicalNodeIds() {
        try {if(SingleDatabase.enabled(config)){SingleDatabase.validate(config);return Set.of(2);}}
        catch(java.sql.SQLException invalid){throw StorageFailure.unavailable();}
        String raw=required("storage.physicalNodeIds");Set<Integer> result=new TreeSet<>();
        for(String value:raw.split(",",-1)){
            try{String id=value.trim();int node=Integer.parseInt(id);if(node<1||!Integer.toString(node).equals(id)||!result.add(node))throw StorageFailure.unavailable();}
            catch(NumberFormatException invalid){throw StorageFailure.unavailable();}
        }
        if(result.size()<2)throw StorageFailure.unavailable();
        return Set.copyOf(result);
    }

    private Set<Integer> onlineIds(JsonNode value,Set<Integer> configured,int local) {
        JsonNode nodes=value.path("onlineNodeIds");Set<Integer> online=new TreeSet<>();
        if(!nodes.isArray())throw StorageFailure.unavailable();
        for(JsonNode item:nodes){if(!item.isInt()||!online.add(item.intValue()))throw StorageFailure.unavailable();}
        if(!configured.containsAll(online)||!online.contains(local))throw StorageFailure.unavailable();
        String mode=value.path("replicationMode").asText();boolean single=value.path("singleNodeAuthorized").asBoolean(false);
        if("dual".equals(mode)){
            if(configured.size()!=2||single||!online.equals(configured))throw StorageFailure.unavailable();
        }else if("quorum".equals(mode)){
            if(single||online.size()<configured.size()/2+1)throw StorageFailure.unavailable();
        }else if("single".equals(mode)){
            if(!single||online.size()!=1)throw StorageFailure.unavailable();
        }else if("fenced".equals(mode)){
            if(single||online.size()<2||online.size()>=configured.size()/2+1)throw StorageFailure.unavailable();
        }else throw StorageFailure.unavailable();
        if(value.path("viewId").asText().isBlank())throw StorageFailure.unavailable();
        JsonNode configuredNodes=value.path("configuredNodeIds");Set<Integer> observedConfig=new TreeSet<>();
        if(!configuredNodes.isArray())throw StorageFailure.unavailable();
        for(JsonNode item:configuredNodes){if(!item.isInt()||!observedConfig.add(item.intValue()))throw StorageFailure.unavailable();}
        if(!observedConfig.equals(configured))throw StorageFailure.unavailable();
        return Set.copyOf(online);
    }

    private Map<Integer,Probe> physicalProbes(String ignored,Map<Integer,URI> endpoints) {
        if(!"true".equals(config.getProperty("storage.replicated")))throw StorageFailure.unavailable();
        Set<Integer> configured=physicalNodeIds();String localText=required("server.id");
        int local;try{local=Integer.parseInt(localText);if(local<1||!Integer.toString(local).equals(localText))throw StorageFailure.unavailable();}catch(NumberFormatException invalid){throw StorageFailure.unavailable();}
        if(!configured.contains(local))throw StorageFailure.unavailable();
        try {
            JsonNode first=fetch(local,endpoints);Probe primary=parse(first,local,System.currentTimeMillis());
            Set<Integer> online=onlineIds(first,configured,local);Map<Integer,Probe> result=new LinkedHashMap<>();result.put(local,primary);
            String digest=first.path("membershipDigest").asText(),localMember=first.path("memberUuid").asText();
            if(!digest.matches("[0-9a-f]{64}")||localMember.isBlank())throw StorageFailure.unavailable();
            Set<String> identities=new HashSet<>();identities.add(localMember);
            for(int node:new TreeSet<>(online))if(node!=local){
                JsonNode peer=fetch(node,endpoints);
                // Atomic BLOB writes use this JVM's local MySQL only. The peer's
                // MySQL/GR/capacity remain mandatory, but its Tomcat is not a
                // data participant and may be the reason ingress forwarded here.
                result.put(node,parse(peer,node,System.currentTimeMillis(),!"atomic".equals(config.getProperty("storage.transactionMode"))));
                if(!digest.equals(peer.path("membershipDigest").asText())||peer.path("memberUuid").asText().isBlank()
                        ||!identities.add(peer.path("memberUuid").asText()))throw StorageFailure.unavailable();
                if("atomic".equals(config.getProperty("storage.transactionMode"))
                        &&(!first.path("viewId").asText().equals(peer.path("viewId").asText())
                        ||!first.path("replicationMode").asText().equals(peer.path("replicationMode").asText())
                        ||!online.equals(onlineIds(peer,configured,node))))throw StorageFailure.unavailable();
            }
            // Recheck the authenticated local view after sampling all replicas.
            JsonNode last=fetch(local,endpoints);parse(last,local,System.currentTimeMillis());
            if(!online.equals(onlineIds(last,configured,local))||!first.path("viewId").asText().equals(last.path("viewId").asText())
                    ||!first.path("replicationMode").asText().equals(last.path("replicationMode").asText())
                    ||!digest.equals(last.path("membershipDigest").asText())||!localMember.equals(last.path("memberUuid").asText()))throw StorageFailure.unavailable();
            return Map.copyOf(result);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw StorageFailure.unavailable();}
        catch(Exception failure){throw StorageFailure.unavailable();}
    }

    /** A fresh authenticated observation of the only database disk. No peer-Tomcat or GR lease is needed. */
    private static StorageFailure capacityReject(OperationalDiagnostics.Reason reason) {
        OperationalDiagnostics.record(reason);return StorageFailure.unavailable();
    }
    private Map<Integer,Probe> singleDatabaseProbe() {
        try {SingleDatabase.validate(config);}catch(Exception unavailable){throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_CONFIG);}
        try {
            java.nio.file.Path temporary=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
            if(!temporary.isAbsolute()||java.nio.file.Files.getFileStore(temporary).getUnallocatedSpace()<FLOOR)throw new java.io.IOException("temporary filesystem unavailable");
        }catch(Exception unavailable){throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_TEMP_FS_FAILURE);}
        String address,token;
        try {address=required("storage.single.capacity.url");if(!"http://127.0.0.1:942/capacity".equals(address))throw new IllegalArgumentException();token=required("storage.probe.token");if(token.length()<32)throw new IllegalArgumentException();}
        catch(Exception unavailable){throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_CONFIG);}
        boolean mobile = SingleDatabase.mobileCompute(config);
        int status;
        byte[] body;
        try {
            if (mobile) {
                // Use the pinned monitor's fixed-length response in the request thread.
                // The emulated VM can delay the asynchronous HTTP executor beyond the proof window.
                long deadline = System.nanoTime() + 4_000_000_000L;
                var connection = (java.net.HttpURLConnection) URI.create(address).toURL().openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(2_000);
                connection.setReadTimeout(4_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Accept", "application/json");
                try {
                    status = connection.getResponseCode();
                    body = new byte[0];
                    if (status == 200) {
                        long length = connection.getContentLengthLong();
                        if (length <= 0 || length > 16_384) throw new java.io.IOException("Invalid capacity response length");
                        try (var input = connection.getInputStream()) {
                            body = input.readNBytes((int) length);
                        }
                        if (body.length != length) throw new java.io.IOException("Incomplete capacity response");
                    }
                    if (System.nanoTime() - deadline > 0) throw new java.net.http.HttpTimeoutException("Capacity request deadline exceeded");
                } finally {
                    connection.disconnect();
                }
            } else {
                HttpRequest request=HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofMillis(1500))
                        .header("Authorization","Bearer "+token).header("Accept","application/json").GET().build();
                BoundedBody subscriber=new BoundedBody();var pending=http.sendAsync(request,ignored->subscriber);
                HttpResponse<byte[]> response;
                try{response=pending.get(1500,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(Exception e){pending.cancel(true);subscriber.cancel();throw e;}
                status = response.statusCode();
                body = response.body();
            }
        }catch(InterruptedException interrupted){OperationalDiagnostics.record(OperationalDiagnostics.Reason.CAPACITY_INTERRUPT);Thread.currentThread().interrupt();throw StorageFailure.unavailable();}
        catch(Exception unavailable){
            throw capacityReject(OperationalDiagnostics.capacityReason(unavailable));
        }
        if(status!=200)throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_BAD_STATUS);
        JsonNode document;
        try {
            if (mobile) {
                try (var parser = ModelGateway.JSON.createParser(body)) {
                    parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                    document = ModelGateway.JSON.readTree(parser);
                    if (parser.nextToken() != null) throw new IllegalArgumentException();
                }
            } else {
                document = ModelGateway.JSON.readTree(body);
            }
            if (document == null || !document.isObject()) throw new IllegalArgumentException();
        }
        catch(Exception unavailable){throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_PARSE_DOCUMENT);}
        try {return Map.of(2,parseSingle(document,config,System.currentTimeMillis()));}
        catch(StorageFailure rejected){throw rejected;}
        catch(Exception unavailable){throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_PARSE_IDENTITY);}
    }
    static Probe parseSingle(JsonNode value,Properties config,long now)throws Exception {
        if (SingleDatabase.mobileCompute(config)
                && (!value.path("nodeId").isInt() || !value.path("mysqlUuid").isTextual()
                    || !value.path("topology").isTextual() || !value.path("mode").isTextual()
                    || !value.path("ready").isBoolean() || !value.path("ready").booleanValue()
                    || !value.path("mysqlReady").isBoolean() || !value.path("mysqlReady").booleanValue()
                    || !value.path("reserveGuaranteed").isBoolean() || !value.path("capacityModelValidated").isBoolean()
                    || !value.path("physicalBytesPerPayloadByte").isIntegralNumber()
                    || !value.path("fixedOverheadBytes").isIntegralNumber()))
            throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_PARSE_IDENTITY);
        if(value.path("nodeId").asInt(-1)!=2||!SingleDatabase.uuid(config).equals(value.path("mysqlUuid").asText())
                ||!"single-database".equals(value.path("topology").asText())||!"single".equals(value.path("mode").asText()))
            throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_PARSE_IDENTITY);
        if(!value.path("observedAtMillis").isIntegralNumber()||!value.path("observedAtMillis").canConvertToLong()
                ||!fresh(value.path("observedAtMillis").longValue(),now))
            throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_PARSE_FRESHNESS);
        if(!value.path("reserveGuaranteed").asBoolean(false)||!value.path("capacityModelValidated").asBoolean(false)
                ||!value.path("floorBytes").isIntegralNumber()||value.path("floorBytes").asLong()!=FLOOR
                ||!value.path("globalReserveEnforcement").isBoolean()||value.path("globalReserveEnforcement").asBoolean())
            throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_PARSE_RESERVE);
        if(value.path("physicalBytesPerPayloadByte").asLong(-1)!=6||value.path("fixedOverheadBytes").asLong(-1)!=30_044_160L)
            throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_PARSE_ESTIMATE);
        JsonNode volumes=value.path("volumes");if(!volumes.isArray()||volumes.isEmpty())throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_PARSE_VOLUMES);
        long available=Long.MAX_VALUE;Set<String> ids=new HashSet<>();
        for(JsonNode volume:volumes){String id=volume.path("id").asText();JsonNode bytes=volume.path("availableBytes");
            if(id.isBlank()||!ids.add(id)||!bytes.isIntegralNumber()||!bytes.canConvertToLong()||bytes.longValue()<0)throw capacityReject(OperationalDiagnostics.Reason.CAPACITY_PARSE_VOLUMES);
            available=Math.min(available,bytes.longValue());
        }
        return new Probe(2,value.path("observedAtMillis").longValue(),available,6,30_044_160L,true,false,"single");
    }

    private JsonNode fetch(int node,Map<Integer,URI> endpoints)throws Exception {
        URI uri=endpoints.get(node);if(uri==null)throw StorageFailure.unavailable();String token=required("storage.probe.token");
        HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                .header("Authorization","Bearer "+token).header("Accept","application/json").GET().build();
        BoundedBody subscriber=new BoundedBody();var pending=http.sendAsync(request,ignored->subscriber);HttpResponse<byte[]> response;
        try{response=pending.get(3,java.util.concurrent.TimeUnit.SECONDS);}
        catch(Exception failure){pending.cancel(true);subscriber.cancel();throw failure;}
        if(response.statusCode()!=200)throw StorageFailure.unavailable();
        JsonNode value=ModelGateway.JSON.readTree(response.body());
        if("atomic".equals(config.getProperty("storage.transactionMode"))&&(!value.path("capacityModelValidated").asBoolean(false)||!value.path("reserveGuaranteed").asBoolean(false)))throw StorageFailure.unavailable();
        return value;
    }

    /** Keep the request deadline active until the entire bounded response arrives. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final java.util.concurrent.CompletableFuture<byte[]> body=new java.util.concurrent.CompletableFuture<>();
        final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(1024);
        volatile java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;if(body.isDone())value.cancel();else value.request(1);}
        public void onNext(List<java.nio.ByteBuffer> buffers){
            for(java.nio.ByteBuffer buffer:buffers){
                if(buffer.remaining()>16_384-bytes.size()){subscription.cancel();body.completeExceptionally(StorageFailure.unavailable());return;}
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.write(part,0,part.length);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure){body.completeExceptionally(failure);}
        public void onComplete(){body.complete(bytes.toByteArray());}
        void cancel(){if(subscription!=null)subscription.cancel();body.completeExceptionally(StorageFailure.unavailable());}
    }

    int ingress(HttpServletRequest request) {
        String id=request.getHeader("X-Starhub-Ingress-Node"),actual=request.getHeader("X-Starhub-Ingress-Token");
        String expected=required("storage.ingress.token");
        // The connector must be private, and Nginx must overwrite both headers even for node 1.
        if(actual==null||!MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8)))
            throw StorageFailure.unavailable();
        if (SingleDatabase.mobileCompute(config)) {
            if (!"3".equals(id)) throw StorageFailure.unavailable();
            try {
                // Durable reservation ownership remains the physical compute ID, while the
                // operation route retains its existing logical database vocabulary.
                return SingleDatabase.mobileLogicalRoute(config);
            } catch (java.sql.SQLException invalid) { throw StorageFailure.unavailable(); }
        }
        if (!("1".equals(id)||"2".equals(id))) throw StorageFailure.unavailable();
        return Integer.parseInt(id);
    }

    String required(String key) { String value=config.getProperty(key);if(value==null||value.isBlank())throw StorageFailure.unavailable();return value; }
}
