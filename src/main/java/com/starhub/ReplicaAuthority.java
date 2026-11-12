package com.starhub;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;

/** Authenticate the local replicated writer and bind each JDBC connection to it. */
final class ReplicaAuthority {
    private ReplicaAuthority() { }
    private record Endpoint(int port,String schema) { }
    record Lease(String mysqlUuid,String stamp,String view,String epoch,String mode,String boot,String membership) { }
    private static final String[] ROUTING_KEYS={"storage.replicated","server.id","db.url","db.user","db.password",
            "storage.node2.db.url","storage.node2.db.user","storage.node2.db.password",
            "storage.objectStore.backend","storage.objectStore.jdbc.url","storage.objectStore.jdbc.user",
            "storage.objectStore.jdbc.password","storage.objectStore","storage.transactionNode",
            "storage.authority.url","storage.probe.token","storage.ingress.token","storage.physicalNodeIds","storage.transactionMode","storage.idempotency.secret","vault.key"};

    static boolean enabled(Properties p) throws SQLException {
        String value=p.getProperty("storage.replicated","false");
        if(!"true".equals(value)&&!"false".equals(value))throw new SQLException("Invalid replicated storage setting");
        return "true".equals(value);
    }

    private static Endpoint endpoint(Properties p,String key) {
        String raw=p.getProperty(key,"");
        if(!raw.startsWith("jdbc:mysql://"))throw new IllegalArgumentException("Missing private replicated MySQL endpoint");
        URI uri=URI.create(raw.substring(5));String path=uri.getRawPath();
        if(!"mysql".equals(uri.getScheme())||!("127.0.0.1".equals(uri.getHost())||"localhost".equals(uri.getHost()))
                ||uri.getPort()<1||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null
                ||path==null||!path.matches("/[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Replicated database must use an explicit private loopback endpoint");
        return new Endpoint(uri.getPort(),path.substring(1));
    }

    static void validateEndpoints(Properties p) {
        Endpoint first=endpoint(p,"db.url"),second=endpoint(p,"storage.node2.db.url"),decision=endpoint(p,"storage.objectStore.jdbc.url");
        if(first.port()!=second.port()||first.port()!=decision.port()||first.schema().equalsIgnoreCase(second.schema()))
            throw new IllegalArgumentException("Replicated logical shards and decisions must use one local MySQL instance");
    }

    static void sameRouting(Properties running,Properties current) throws SQLException {
        for(String key:ROUTING_KEYS)if(!java.util.Objects.equals(running.getProperty(key),current.getProperty(key)))
            throw new SQLException("Replicated storage configuration changed; restart the coordinator");
        java.util.Set<String> keys=new java.util.HashSet<>();keys.addAll(running.stringPropertyNames());keys.addAll(current.stringPropertyNames());
        for(String key:keys)if(key.matches("storage\\.node[1-9][0-9]*\\.probe\\.url")&&!java.util.Objects.equals(running.getProperty(key),current.getProperty(key)))
            throw new SQLException("Replicated capacity observers changed; restart the coordinator");
    }

    static Lease lease(Properties p) throws SQLException { return authority(p,true); }
    static void unchanged(Properties p,Lease expected)throws SQLException {
        if(expected==null||!expected.equals(lease(p)))throw new SQLException("Replicated authority changed; query the original operation result");
    }
    private static URI observer(Properties p)throws SQLException {
        try{
            URI uri=URI.create(p.getProperty("storage.authority.url",""));
            if(!"http".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||uri.getPort()<1||uri.getPort()>65535
                    ||!"/authority".equals(uri.getRawPath())||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)
                throw new SQLException("Replicated authority must be an explicit local observer");
            return uri;
        }catch(IllegalArgumentException invalid){throw new SQLException("Invalid private authority endpoint",invalid);}
    }
    private static String token(Properties p)throws SQLException {
        String value=p.getProperty("storage.probe.token","");if(value.length()<32)throw new SQLException("Missing replicated authority token");return value;
    }
    private static boolean exactConfirmation(HttpURLConnection connection,Lease lease){
        return lease!=null&&java.util.Objects.equals(lease.mode(),connection.getHeaderField("X-Four-Project-Durability"))
                &&java.util.Objects.equals(lease.mysqlUuid(),connection.getHeaderField("X-Four-Project-MySQL-UUID"))
                &&java.util.Objects.equals(lease.stamp(),connection.getHeaderField("X-Four-Project-Permit"))
                &&java.util.Objects.equals(lease.view(),connection.getHeaderField("X-Four-Project-View-ID"))
                &&java.util.Objects.equals(lease.epoch(),connection.getHeaderField("X-Four-Project-Epoch"))
                &&java.util.Objects.equals(lease.mode(),connection.getHeaderField("X-Four-Project-Mode"))
                &&java.util.Objects.equals(lease.boot(),connection.getHeaderField("X-Four-Project-Controller-Boot-ID"))
                &&java.util.Objects.equals(lease.membership(),connection.getHeaderField("X-Four-Project-Membership"));
    }
    static void confirm(Properties p,Lease lease,String gtidSet,String operation)throws SQLException {
        HttpURLConnection connection=null;
        try {
            URI uri=observer(p);String token=token(p);
            if(lease==null||java.util.stream.Stream.of(lease.mysqlUuid(),lease.stamp(),lease.view(),lease.epoch(),lease.mode(),lease.boot(),lease.membership()).anyMatch(value->value==null||value.isBlank()))throw new SQLException("A complete authority lease is required");
            connection=(HttpURLConnection)new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),"/confirm",null,null).toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(1500);connection.setReadTimeout(120000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Authorization","Bearer "+token);
            connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Cache-Control","no-store");
            byte[] bytes=ModelGateway.JSON.writeValueAsBytes(ModelGateway.JSON.createObjectNode().put("permit",lease.stamp()).put("gtidSet",gtidSet).put("operationId",operation));
            if(bytes.length>65536)throw new SQLException("Durability confirmation exceeds bounded request size");
            connection.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream output=connection.getOutputStream()){output.write(bytes);}
            if(connection.getResponseCode()!=204||!exactConfirmation(connection,lease))
                throw new SQLException("Durable replication confirmation is unavailable; query the original operation result");
        }catch(SQLException failure){throw failure;}
        catch(Exception failure){throw new SQLException("Durable replication confirmation is unavailable",failure);}
        finally{if(connection!=null)connection.disconnect();}
    }
    private static String identity(Properties p) throws SQLException { return authority(p,"atomic".equals(p.getProperty("storage.transactionMode"))).mysqlUuid(); }
    private static Lease authority(Properties p,boolean requireLease) throws SQLException {
        String token=token(p);
        HttpURLConnection connection=null;
        try {
            URI uri=observer(p);
            connection=(HttpURLConnection)uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(1500);connection.setReadTimeout(8000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("Cache-Control","no-store");
            if(connection.getResponseCode()!=204)throw new SQLException("Replicated authority is unavailable");
            String value=connection.getHeaderField("X-Four-Project-MySQL-UUID");
            if(value==null)throw new SQLException("Replicated authority identity is unavailable");
            String normalized=UUID.fromString(value).toString();
            if(!normalized.equals(value.toLowerCase(Locale.ROOT)))throw new SQLException("Invalid replicated authority identity");
            String stamp=connection.getHeaderField("X-Four-Project-Permit"),view=connection.getHeaderField("X-Four-Project-View-ID"),
                    epoch=connection.getHeaderField("X-Four-Project-Epoch"),mode=connection.getHeaderField("X-Four-Project-Mode"),boot=connection.getHeaderField("X-Four-Project-Controller-Boot-ID"),
                    membership=connection.getHeaderField("X-Four-Project-Membership");
            if(requireLease&&(stamp==null||!stamp.matches("[0-9a-f]{64}")||view==null||view.isBlank()||view.length()>200
                    ||epoch==null||!epoch.matches("[1-9][0-9]{0,19}")||!("dual".equals(mode)||"single".equals(mode))
                    ||boot==null||!UUID.fromString(boot).toString().equals(boot)||membership==null||!membership.matches("[0-9a-f]{64}")))throw new SQLException("Replicated authority lease is unavailable");
            return new Lease(normalized,stamp,view,epoch,mode,boot,membership);
        } catch(SQLException failure){throw failure;}
        catch(Exception failure){throw new SQLException("Replicated authority is unavailable",failure);}
        finally{if(connection!=null)connection.disconnect();}
    }

    static void verify(Connection connection,Properties p) throws SQLException {
        if(!enabled(p))return;
        String expected=identity(p);
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT @@server_uuid,@@session.sql_log_bin,@@session.group_replication_consistency")) {
            if(!rows.next())throw new SQLException("MySQL identity is unavailable");
            String actual=rows.getString(1);
            try {
                if(actual==null||!UUID.fromString(actual).toString().equals(expected))throw new SQLException("Database is not the authorized replicated writer");
            } catch(IllegalArgumentException invalid){throw new SQLException("Invalid database identity",invalid);}
            String consistency=rows.getString(3);
            if(rows.getInt(2)!=1||!("AFTER".equals(consistency)||"BEFORE_AND_AFTER".equals(consistency))
                    ||("atomic".equals(p.getProperty("storage.transactionMode"))&&!"BEFORE_AND_AFTER".equals(consistency)))
                throw new SQLException("Database session does not guarantee replicated writes");
        }
    }

    static Connection checked(Connection connection,Properties p) throws SQLException {
        try{verify(connection,p);return connection;}
        catch(SQLException|RuntimeException failure){try{connection.close();}catch(SQLException close){failure.addSuppressed(close);}throw failure;}
    }
}
