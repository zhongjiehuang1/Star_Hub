package com.starhub;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Explicit one-physical-database topology. The identity marker is not an authority grant. */
final class SingleDatabase {
    private SingleDatabase() {}
    static boolean enabled(Properties p)throws SQLException {
        String topology=p.getProperty("storage.topology","replicated");
        if(!Set.of("replicated","single-database").contains(topology))throw new SQLException("Invalid storage topology");
        if("single-database".equals(topology)) {
            if(!"true".equals(p.getProperty("storage.replicated"))||!"atomic".equals(p.getProperty("storage.transactionMode")))throw new SQLException("Single database requires atomic transactions");
            return true;
        }
        return false;
    }
    static String uuid(Properties p)throws SQLException {
        try {String value=p.getProperty("storage.mysqlUuid","");if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();return value;}
        catch(IllegalArgumentException e){throw new SQLException("Single database identity is invalid",e);}
    }
    static int port(Properties p)throws SQLException {
        try {String v=p.getProperty("storage.single.mysqlPort","");int port=Integer.parseInt(v);if(port<1||port>65535||!Integer.toString(port).equals(v))throw new IllegalArgumentException();return port;}
        catch(IllegalArgumentException e){throw new SQLException("Single database server port is invalid",e);}
    }
    static void validate(Properties p)throws SQLException {
        if(!enabled(p))throw new SQLException("Single database topology required");
        uuid(p);port(p);
        String host=p.getProperty("server.id",""),transport=p.getProperty("storage.single.transport","");
        if(!Set.of("1","2").contains(host)||!Set.of("local","verified-ssh-tunnel","local-or-verified-ssh-tunnel").contains(transport)
            ||("1".equals(host)&&"local".equals(transport))||("2".equals(host)&&"verified-ssh-tunnel".equals(transport)))throw new SQLException("Single database transport is not configured");
        try {ReplicaAuthority.validateEndpoints(p);} catch(IllegalArgumentException invalid){throw new SQLException("Single database routes invalid",invalid);}
        for(String key:List.of("db.url","storage.node2.db.url","storage.objectStore.jdbc.url")) requireTls(p.getProperty(key,""));
        if(!"2".equals(p.getProperty("storage.physicalNodeIds","")))throw new SQLException("Single database must reserve only server 2");
    }
    private static void requireTls(String url)throws SQLException {
        try {
            URI uri=URI.create(url.substring(5));String query=uri.getRawQuery();if(query==null)throw new IllegalArgumentException();
            Map<String,String> security=new HashMap<>();
            for(String part:query.split("&")) {
                String[] pair=part.split("=",2);String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if(Set.of("sslmode","usessl","requiressl","verifyservercertificate").contains(key)) {
                    if(pair.length!=2||security.putIfAbsent(key,URLDecoder.decode(pair[1],StandardCharsets.UTF_8))!=null)throw new IllegalArgumentException();
                }
            }
            if(!"VERIFY_IDENTITY".equalsIgnoreCase(security.getOrDefault("sslmode","")))throw new IllegalArgumentException();
            for(String key:List.of("usessl","requiressl","verifyservercertificate"))if(security.containsKey(key)&&!"true".equalsIgnoreCase(security.get(key)))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new SQLException("Verified MySQL TLS is required",invalid);}
    }
    static ReplicaAuthority.Lease marker(Properties p)throws SQLException {
        validate(p);String id=uuid(p),hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("starhub-single-database-identity-v1:"+id).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new SQLException("Identity marker unavailable",e);}
        return new ReplicaAuthority.Lease(id,hash,"single-database:"+id,"1","single",id,hash);
    }
    /** Called on the actual connection before work and on both sides of commit. */
    static void verify(Connection c,Properties p)throws SQLException {
        validate(p);String expected=uuid(p);int serverPort=port(p);
        Set<String> schemas=new HashSet<>();
        for(String key:List.of("db.url","storage.node2.db.url","storage.objectStore.jdbc.url"))try{schemas.add(URI.create(p.getProperty(key).substring(5)).getPath().substring(1));}catch(RuntimeException e){throw new SQLException("Invalid single database schema",e);}
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT @@server_uuid,@@GLOBAL.read_only,@@GLOBAL.super_read_only,@@GLOBAL.innodb_flush_log_at_trx_commit,@@GLOBAL.sync_binlog,@@GLOBAL.log_bin,DATABASE(),@@port,@@SESSION.sql_log_bin")) {
            if(!r.next()||!expected.equals(r.getString(1))||r.getInt(2)!=0||r.getInt(3)!=0||r.getInt(4)!=1||r.getInt(5)!=1||r.getInt(6)!=1||!schemas.contains(r.getString(7))||r.getInt(8)!=serverPort||r.getInt(9)!=1)throw new SQLException("Single database identity or durability is unavailable");
        }
    }
}
