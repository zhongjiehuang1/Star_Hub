package com.starhub;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Explicit read-only scratch diagnostics; never starts recovery, locks, writes or services. */
public final class AtomicRuntimeDiagnostics {
    @FunctionalInterface interface Check {void run()throws Exception;}
    private static Properties properties;
    private static boolean failed;
    private static void check(String label,Check check){
        try{check.run();System.out.println("PASS "+label);}catch(Throwable problem){failed=true;String message=Objects.toString(problem.getMessage(),"");
            for(String key:properties.stringPropertyNames())if(key.matches("(?i).*password.*|.*token.*|.*secret.*|.*vault.*|.*url.*")){String value=properties.getProperty(key);if(value!=null&&value.length()>=4)message=message.replace(value,"[REDACTED]");}
            message=message.replaceAll("jdbc:mysql://[^\\s)]+","[JDBC_URL_REDACTED]").replaceAll("[\\r\\n]"," ");if(message.length()>800)message=message.substring(0,800);
            System.out.println("FAIL "+label+" type="+problem.getClass().getSimpleName()+(problem instanceof SQLException sql?" state="+sql.getSQLState()+" code="+sql.getErrorCode():"")+" detail="+message);
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Private scratch config required");Path file=Path.of(args[0]).toAbsolutePath().normalize();
        if(!file.startsWith(Path.of("/var/lib/codex-validation/app-config/")))throw new IllegalArgumentException("Scratch config only");
        properties=new Properties();try(InputStream input=Files.newInputStream(file)){properties.load(input);}
        for(String key:List.of("db.url","storage.node2.db.url","storage.objectStore.jdbc.url")){
            String url=properties.getProperty(key,"");if(!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/starhub_(?:accounts|files_peer|decisions|atomic_test_[a-z]+)[?].*"))throw new IllegalArgumentException("Scratch StarHub schemas only");
        }
        String identity=Files.readString(Path.of(properties.getProperty("storage.objectStore")).resolve("identity")).trim();
        check("authority",()->ReplicaAuthority.lease(properties));
        check("primary-runtime-jdbc",()->{try(Connection c=AccountServlet.unscopedDb(properties);Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT @@session.time_zone,@@session.group_replication_consistency")){if(!r.next()||!"+00:00".equals(r.getString(1))||!"BEFORE_AND_AFTER".equals(r.getString(2)))throw new SQLException("UTC/consistency mismatch");}});
        check("legacy-decision-readonly",()->JdbcDecisionStore.from(properties,identity));
        check("atomic-schema-drained",()->new AtomicDatabase(properties).verifyDrainedSchema());
        check("actual-runtime-probe",()->new AtomicDatabase(properties).runtimeProbe(identity));
        if(failed)System.exit(2);
    }
}
