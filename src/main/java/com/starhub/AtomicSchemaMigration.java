package com.starhub;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Produce reviewable restrictive-FK DDL; never edits data or disables checks. */
public final class AtomicSchemaMigration {
    private AtomicSchemaMigration() { }
    private static String quote(String id)throws SQLException {if(id==null||!id.matches("[A-Za-z0-9_]+"))throw new SQLException("Unrecognized schema identifier");return "`"+id+"`";}
    private static final class Key {
        String schema,table,name,parentSchema,parentTable;boolean cascading;
        final SortedMap<Integer,String> columns=new TreeMap<>(),parents=new TreeMap<>();
    }
    public static List<String> plan(Connection connection,Set<String> schemas)throws Exception {
        List<String> statements=new ArrayList<>();DatabaseMetaData meta=connection.getMetaData();
        for(String schema:new TreeSet<>(schemas)){
            quote(schema);
            try(ResultSet tables=meta.getTables(schema,null,"%",new String[]{"TABLE"})){
                while(tables.next()){
                    String table=tables.getString("TABLE_NAME");Map<String,Key> keys=new TreeMap<>();
                    try(ResultSet rows=meta.getImportedKeys(schema,null,table)){
                        while(rows.next()){
                            String name=rows.getString("FK_NAME");Key key=keys.computeIfAbsent(name,ignored->new Key());
                            key.schema=schema;key.table=table;key.name=name;key.parentSchema=rows.getString("PKTABLE_CAT");key.parentTable=rows.getString("PKTABLE_NAME");
                            if(!schemas.contains(key.parentSchema))throw new SQLException("Cross-schema FK outside the approved atomic schemas");
                            int sequence=rows.getInt("KEY_SEQ");key.columns.put(sequence,rows.getString("FKCOLUMN_NAME"));key.parents.put(sequence,rows.getString("PKCOLUMN_NAME"));
                            for(String rule:List.of("DELETE_RULE","UPDATE_RULE")){short value=rows.getShort(rule);key.cascading|=value!=DatabaseMetaData.importedKeyRestrict&&value!=DatabaseMetaData.importedKeyNoAction;}
                        }
                    }
                    for(Key key:keys.values())if(key.cascading){
                        String original=key.schema+"."+key.table+"."+key.name;
                        String replacement="atomic_"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))).substring(0,24);
                        List<String> columns=new ArrayList<>(),parents=new ArrayList<>();for(String column:key.columns.values())columns.add(quote(column));for(String column:key.parents.values())parents.add(quote(column));
                        // A different new name avoids MySQL 1826 when DROP and ADD
                        // are performed by the same atomic ALTER TABLE statement.
                        statements.add("ALTER TABLE "+quote(key.schema)+"."+quote(key.table)+" DROP FOREIGN KEY "+quote(key.name)
                                +", ADD CONSTRAINT "+quote(replacement)+" FOREIGN KEY ("+String.join(",",columns)+") REFERENCES "+quote(key.parentSchema)+"."+quote(key.parentTable)
                                +" ("+String.join(",",parents)+") ON DELETE RESTRICT ON UPDATE RESTRICT;");
                    }
                }
            }
        }
        return List.copyOf(statements);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!"--plan-only".equals(args[1]))throw new IllegalArgumentException("Supply private runtime properties and --plan-only; apply reviewed DDL separately with all writers fenced");
        Properties p=new Properties();try(var reader=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(args[0]))){p.load(reader);}
        ReplicaAuthority.validateEndpoints(p);Set<String> schemas=new HashSet<>();
        for(String key:List.of("db.url","storage.node2.db.url"))schemas.add(java.net.URI.create(p.getProperty(key).substring(5)).getPath().substring(1));
        Class.forName("com.mysql.cj.jdbc.Driver");
        try(Connection c=DriverManager.getConnection(AccountServlet.jdbcUrl(p,"db.url"),p.getProperty("db.user"),p.getProperty("db.password"))){
            if(!c.getMetaData().getDatabaseProductVersion().startsWith("26.10."))throw new SQLException("Current MySQL 26.10 is required");
            for(String ddl:plan(c,schemas))System.out.println(ddl);
        }
    }
}
