package com.starhub;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Explicit maintenance tool. An external operator must fence both application writers. */
public final class SingleDatabaseMigrationCli {
    private static final Map<String,String> CHECKS=Map.ofEntries(
        Map.entry("file_pending", "SELECT COUNT(*) FROM file_storage_operations WHERE state IS NULL OR state NOT IN ('COMMITTED','ABORTED','CANCELLED')"),
        Map.entry("provider_pending", "SELECT COUNT(*) FROM starhub_provider_operations WHERE state IS NULL OR state NOT IN ('COMPLETED','COMPLETED_NO_RESULT')"),
        Map.entry("file_reservations", "SELECT COUNT(*) FROM starhub_reserved_capacity"),
        Map.entry("provider_reservations", "SELECT COUNT(*) FROM starhub_provider_capacity"),
        Map.entry("user_pending", "SELECT COUNT(*) FROM file_storage_users WHERE reserved_bytes<>0 OR active_op IS NOT NULL"),
        Map.entry("xa_branches_pending", "SELECT COUNT(*) FROM file_storage_branches WHERE terminal IS NULL OR terminal NOT IN ('COMMITTED','ABORTED')"),
        Map.entry("legacy_reserved", "SELECT COUNT(*) FROM file_storage_nodes WHERE reserved_bytes<>0"),
        Map.entry("payload_pending", "SELECT COUNT(*) FROM starhub_payload_manifests WHERE state IS NULL OR state NOT IN ('PUBLISHED','CANCELLED')"),
        Map.entry("guest_cleanup_pending", "SELECT COUNT(*) FROM starhub_guest_cleanup WHERE state IS NULL OR state NOT IN ('CANCELLED','COMPLETED')"),
        Map.entry("guest_staging_pending", "SELECT COUNT(*) FROM starhub_guest_staging"),
        Map.entry("login_pending", "SELECT COUNT(*) FROM starhub_login_attempts WHERE outcome_status IS NULL OR outcome_status NOT IN (200,401,403,429)")
    );
    private SingleDatabaseMigrationCli() {}
    private static void privateFile(Path path)throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Private configuration must be a regular non-symlink file");
        Set<PosixFilePermission> mode=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
        if(!Collections.disjoint(mode,EnumSet.of(PosixFilePermission.GROUP_READ,PosixFilePermission.GROUP_WRITE,PosixFilePermission.GROUP_EXECUTE,PosixFilePermission.OTHERS_READ,PosixFilePermission.OTHERS_WRITE,PosixFilePermission.OTHERS_EXECUTE)))throw new IOException("Private configuration must not be accessible by group or others");
    }
    private static String sha256(Path path)throws Exception {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Backup ciphertext must be a regular non-symlink file");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>=0;)if(n>0)digest.update(b,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static Map<String,Long> state(Connection c,Properties config)throws SQLException {
        Map<String,Long> counts=new TreeMap<>();
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("XA RECOVER")){long n=0;while(r.next())n++;counts.put("prepared_xa",n);}
        String decisionSchema=java.net.URI.create(config.getProperty("storage.objectStore.jdbc.url").substring(5)).getPath().substring(1);
        if(!decisionSchema.matches("[A-Za-z0-9_]+"))throw new SQLException("Invalid decision schema");
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM `"+decisionSchema+"`.`starhub_JBossTSTxTable`")){if(!r.next())throw new SQLException("Decision count unavailable");counts.put("legacy_xa_decisions",r.getLong(1));}
        for(var entry:CHECKS.entrySet())try(Statement q=c.createStatement();ResultSet r=q.executeQuery(entry.getValue())){if(!r.next())throw new SQLException("Preflight count unavailable");counts.put(entry.getKey(),r.getLong(1));}
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT node_id,reserved_bytes FROM starhub_capacity_totals ORDER BY node_id")){while(r.next()){int node=r.getInt(1);if(node!=1&&node!=2)throw new SQLException("Unexpected accounting node");counts.put("capacity_node_"+node,r.getLong(2));}}
        return counts;
    }
    private static void zeroPending(Map<String,Long> state)throws SQLException {
        for(var e:state.entrySet())if(!e.getKey().startsWith("capacity_node_")&&e.getValue()!=0)throw new SQLException("Pending work exists; reconcile before migration");
        if(!Objects.equals(state.get("capacity_node_1"),0L)||!Objects.equals(state.get("capacity_node_2"),0L))throw new SQLException("Expected two zero capacity rows");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=5||!"--writers-stopped-backup-authenticated".equals(args[4])||!args[3].matches("[0-9a-f]{64}")||!Set.of("--inspect","--apply").contains(args[0]))throw new IllegalArgumentException("Usage: --inspect|--apply PRIVATE_CONFIG BACKUP_CIPHERTEXT BACKUP_SHA256 --writers-stopped-backup-authenticated; external operator must prove both writers stopped and authenticate backup separately");
        Path config=Path.of(args[1]),backup=Path.of(args[2]);privateFile(config);
        if(!MessageDigest.isEqual(sha256(backup).getBytes(java.nio.charset.StandardCharsets.US_ASCII),args[3].getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new IOException("Backup ciphertext SHA-256 mismatch");
        Properties p=new Properties();try(InputStream in=Files.newInputStream(config)){p.load(in);}SingleDatabase.validate(p);
        Class.forName("com.mysql.cj.jdbc.Driver");
        Properties credentials=new Properties();credentials.setProperty("user",p.getProperty("db.user"));credentials.setProperty("password",p.getProperty("db.password"));
        try(Connection c=DriverManager.getConnection(AccountServlet.jdbcUrl(p,"db.url"),credentials)){
            SingleDatabase.verify(c,p);Map<String,Long> before=state(c,p);System.out.println("before="+ModelGateway.JSON.writeValueAsString(before));zeroPending(before);
            if("--apply".equals(args[0])){
                SingleDatabaseMigration.migrateCapacity(c,p);Map<String,Long> after=state(c,p);
                if(after.containsKey("capacity_node_1")||!Objects.equals(after.get("capacity_node_2"),0L))throw new SQLException("After-state accounting differs from expectation; reconcile manually");
                for(var e:after.entrySet())if(!e.getKey().startsWith("capacity_node_")&&e.getValue()!=0)throw new SQLException("After-state contains pending work; reconcile manually");
                System.out.println("after="+ModelGateway.JSON.writeValueAsString(after));
            }
        }
    }
}
