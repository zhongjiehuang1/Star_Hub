package com.starhub;

import java.sql.*;
import java.util.*;

/** Required account-feature schema; metadata checks are read-only and fail closed. */
final class AccountFeatureSchema {
    private AccountFeatureSchema(){}
    private record Column(int type,int size,int scale,int nullable,String defaultValue){}
    private record Index(boolean unique,SortedMap<Integer,String> columns){}

    static void verify(Connection c,Properties config)throws SQLException {
        Set<String> tables=new HashSet<>(Set.of("starhub_memory_settings","starhub_account_memories"));
        if(AccountServlet.idleEnabled(config))tables.addAll(Set.of("starhub_account_idle_gate","starhub_account_idle_sessions"));
        engines(c,tables);
        // The current chat path reads memory settings even when the account is
        // opted out, so 018 is mandatory for this application build.
        Map<String,Column> settings=columns(c,"starhub_memory_settings");
        big(settings,"user_id");text(settings,"owner_generation",36);flag(settings,"enabled");big(settings,"revision");
        index(c,"starhub_memory_settings",true,"user_id","owner_generation");restrictOwner(c,"starhub_memory_settings");

        Map<String,Column> memories=columns(c,"starhub_account_memories");
        text(memories,"id",36);big(memories,"user_id");text(memories,"owner_generation",36);text(memories,"kind",32);
        binaryExact(memories,"fingerprint",32);binaryExact(memories,"nonce",12);binary(memories,"ciphertext",2048);
        timestamp(c,"starhub_account_memories",memories,"created_at");timestamp(c,"starhub_account_memories",memories,"updated_at");
        index(c,"starhub_account_memories",true,"id");
        index(c,"starhub_account_memories",true,"user_id","owner_generation","fingerprint");
        index(c,"starhub_account_memories",false,"user_id","owner_generation","updated_at","created_at","id");
        restrictOwner(c,"starhub_account_memories");

        if(AccountServlet.idleEnabled(config)){
            Map<String,Column> gate=columns(c,"starhub_account_idle_gate");flag(gate,"id");big(gate,"last_clock_millis");
            index(c,"starhub_account_idle_gate",true,"id");
            try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT last_clock_millis FROM starhub_account_idle_gate WHERE id=1")){
                if(!r.next()||r.getLong(1)<0||r.wasNull()||r.next())throw new SQLException("Account idle clock row unavailable");
            }
            Map<String,Column> sessions=columns(c,"starhub_account_idle_sessions");
            text(sessions,"session_nonce",36);big(sessions,"user_id");text(sessions,"user_generation",36);big(sessions,"last_activity_millis");flag(sessions,"revoked");
            index(c,"starhub_account_idle_sessions",true,"session_nonce");
            index(c,"starhub_account_idle_sessions",false,"last_activity_millis");
            index(c,"starhub_account_idle_sessions",false,"user_id","user_generation","last_activity_millis");
        }
    }
    private static String catalog(Connection c)throws SQLException{return c.getCatalog();}
    private static String schema(Connection c)throws SQLException{return c.getSchema();}
    private static void engines(Connection c,Set<String> tables)throws SQLException {
        if(!"MySQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName()))return;
        Set<String> found=new HashSet<>();
        try(PreparedStatement q=c.prepareStatement("SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=?")){
            q.setString(1,catalog(c));try(ResultSet r=q.executeQuery()){while(r.next()){
                String name=r.getString(1).toLowerCase(Locale.ROOT);if(!tables.contains(name))continue;
                if(!"InnoDB".equalsIgnoreCase(r.getString(2))||!found.add(name))throw new SQLException("Account feature table is not transactional: "+name);
            }}
        }
        if(!found.equals(tables))throw new SQLException("Required account feature tables unavailable");
    }
    private static Map<String,Column> columns(Connection c,String table)throws SQLException {
        Map<String,Column> result=new HashMap<>();
        try(ResultSet r=c.getMetaData().getColumns(catalog(c),schema(c),table,null)){
            while(r.next())result.put(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),new Column(r.getInt("DATA_TYPE"),r.getInt("COLUMN_SIZE"),r.getInt("DECIMAL_DIGITS"),r.getInt("NULLABLE"),r.getString("COLUMN_DEF")));
        }
        if(result.isEmpty())throw new SQLException("Required account feature table unavailable: "+table);return result;
    }
    private static Column required(Map<String,Column> columns,String name)throws SQLException {
        Column value=columns.get(name);if(value==null||value.nullable()!=DatabaseMetaData.columnNoNulls)throw new SQLException("Required account feature column unavailable: "+name);return value;
    }
    private static void big(Map<String,Column> columns,String name)throws SQLException{if(required(columns,name).type()!=Types.BIGINT)throw new SQLException("Account feature bigint column changed: "+name);}
    private static void flag(Map<String,Column> columns,String name)throws SQLException{if(!Set.of(Types.BOOLEAN,Types.BIT,Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT).contains(required(columns,name).type()))throw new SQLException("Account feature flag column changed: "+name);}
    private static void text(Map<String,Column> columns,String name,int min)throws SQLException{Column v=required(columns,name);if(!Set.of(Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR).contains(v.type())||v.size()<min)throw new SQLException("Account feature text column changed: "+name);}
    private static void binary(Map<String,Column> columns,String name,int min)throws SQLException{Column v=required(columns,name);if(!Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY).contains(v.type())||v.size()<min)throw new SQLException("Account feature binary column changed: "+name);}
    private static void binaryExact(Map<String,Column> columns,String name,int size)throws SQLException{Column v=required(columns,name);if(!Set.of(Types.BINARY,Types.VARBINARY).contains(v.type())||v.size()!=size)throw new SQLException("Account feature fixed binary column changed: "+name);}
    private static void timestamp(Connection c,String table,Map<String,Column> columns,String name)throws SQLException {
        Column v=required(columns,name);String value=v.defaultValue()==null?"":v.defaultValue().toUpperCase(Locale.ROOT);
        int precision=v.scale();
        // Connector/J reports DECIMAL_DIGITS=0 for MySQL TIMESTAMP(3); query
        // the authoritative MySQL metadata rather than inferring precision.
        if("MySQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName())){
            try(PreparedStatement q=c.prepareStatement("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?")){
                q.setString(1,catalog(c));q.setString(2,table);q.setString(3,name);try(ResultSet r=q.executeQuery()){if(!r.next())throw new SQLException("Account feature timestamp metadata unavailable: "+name);precision=r.getInt(1);if(r.wasNull()||r.next())throw new SQLException("Account feature timestamp metadata invalid: "+name);}
            }
        }
        if(v.type()!=Types.TIMESTAMP||precision<3||!value.contains("CURRENT_TIMESTAMP"))throw new SQLException("Account feature timestamp column changed: "+name);
    }
    private static void index(Connection c,String table,boolean unique,String... expected)throws SQLException {
        Map<String,Index> indexes=new HashMap<>();Set<String> prefixIndexes=new HashSet<>();
        // JDBC getIndexInfo does not expose MySQL SUB_PART. A unique prefix
        // does not enforce the required full-column uniqueness.
        if("MySQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName()))try(PreparedStatement q=c.prepareStatement("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND SUB_PART IS NOT NULL")){
            q.setString(1,catalog(c));q.setString(2,table);try(ResultSet r=q.executeQuery()){while(r.next())prefixIndexes.add(r.getString(1).toLowerCase(Locale.ROOT));}
        }
        try(ResultSet r=c.getMetaData().getIndexInfo(catalog(c),schema(c),table,false,false)){
            while(r.next()){
                String name=r.getString("INDEX_NAME"),column=r.getString("COLUMN_NAME");if(name==null||column==null)continue;
                if(prefixIndexes.contains(name.toLowerCase(Locale.ROOT)))continue;
                boolean isUnique=!r.getBoolean("NON_UNIQUE");Index found=indexes.computeIfAbsent(name,ignored->new Index(isUnique,new TreeMap<>()));
                found.columns().put(r.getInt("ORDINAL_POSITION"),column.toLowerCase(Locale.ROOT));
            }
        }
        List<String> want=List.of(expected);
        for(Index found:indexes.values()){
            List<String> actual=new ArrayList<>(found.columns().values());
            if((!unique||found.unique())&&actual.size()>=want.size()&&actual.subList(0,want.size()).equals(want)&&(!unique||actual.size()==want.size()))return;
        }
        throw new SQLException("Required account feature index unavailable: "+table+" "+want);
    }
    private static void restrictOwner(Connection c,String table)throws SQLException {
        try(ResultSet r=c.getMetaData().getImportedKeys(catalog(c),schema(c),table)){
            while(r.next())if("user_id".equalsIgnoreCase(r.getString("FKCOLUMN_NAME"))&&"users".equalsIgnoreCase(r.getString("PKTABLE_NAME"))&&"id".equalsIgnoreCase(r.getString("PKCOLUMN_NAME"))
                    &&r.getShort("DELETE_RULE")==DatabaseMetaData.importedKeyRestrict&&r.getShort("UPDATE_RULE")==DatabaseMetaData.importedKeyRestrict)return;
        }
        throw new SQLException("Required account owner restriction unavailable: "+table);
    }
}
