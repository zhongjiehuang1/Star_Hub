package com.starhub;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** Public bounded reads use a fresh checked SQL session and an explicit confirmed snapshot barrier. */
final class BoundedReadScope implements AutoCloseable {
    interface Authority {
        ReplicaAuthority.Lease acquire()throws Exception;
        void unchanged(ReplicaAuthority.Lease lease)throws Exception;
        void confirm(ReplicaAuthority.Lease lease,String gtid,String id)throws Exception;
    }
    final Connection view;
    private final Connection connection;
    private final Authority authority;
    private final ReplicaAuthority.Lease lease;
    private final String beforeGtid;
    private final Properties singleConfig;
    static BoundedReadScope open(Properties config)throws Exception {
        ReplicaAuthority.CheckedOpen checked=ReplicaAuthority.captureCheckedOpen(config);
        Authority authority=new Authority(){
            public ReplicaAuthority.Lease acquire()throws Exception{return checked.lease();}
            public void unchanged(ReplicaAuthority.Lease lease)throws Exception{ReplicaAuthority.validateCheckedOpen(config,checked);ReplicaAuthority.unchanged(config,lease);}
            public void confirm(ReplicaAuthority.Lease lease,String gtid,String id)throws Exception{ReplicaAuthority.validateCheckedOpen(config,checked);ReplicaAuthority.confirm(config,lease,gtid,id);}
        };
        ReplicaAuthority.Lease lease=authority.acquire();Connection c=AccountServlet.unscopedDb(config,checked);
        try{return new BoundedReadScope(c,authority,lease,config);}catch(Exception e){SingleDatabaseConnections.invalidate(c);c.close();throw e;}
    }
    BoundedReadScope(Connection c,Authority authority,ReplicaAuthority.Lease lease)throws Exception {this(c,authority,lease,null);}
    BoundedReadScope(Connection c,Authority authority,ReplicaAuthority.Lease lease,Properties config)throws Exception {
        this.connection=c;this.authority=authority;this.lease=lease;this.singleConfig=config!=null&&SingleDatabase.enabled(config)?config:null;
        if(lease==null||!Set.of("dual","single").contains(lease.mode()))throw new SQLException("Fresh read authority unavailable");
        authority.unchanged(lease);
        if(singleConfig!=null){SingleDatabase.verify(c,singleConfig);beforeGtid="single-database";}
        else {
            // Existing multi-primary reads retain their required replication barrier.
            try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT @@global.gtid_executed,@@session.group_replication_consistency")){
                if(!r.next()||!"BEFORE_AND_AFTER".equals(r.getString(2))||(beforeGtid=r.getString(1))==null||beforeGtid.isBlank())throw new SQLException("Read replication barrier unavailable");
            }
        }
        c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);c.setAutoCommit(false);
        try(Statement q=c.createStatement()){q.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");}
        view=readOnly(c);
    }
    String finish()throws Exception {
        try {authority.unchanged(lease);
        if(singleConfig!=null){SingleDatabase.verify(connection,singleConfig);authority.unchanged(lease);return "single";}
        }catch(Exception failure){SingleDatabaseConnections.invalidate(connection);throw failure;}
        String observedGtid;
        // Global GTID is read AFTER the snapshot data; it over-confirms every transaction the snapshot could expose.
        try(Statement q=connection.createStatement();ResultSet r=q.executeQuery("SELECT @@global.gtid_executed")){if(!r.next()||(observedGtid=r.getString(1))==null||observedGtid.isBlank())throw new SQLException("Read end-position unavailable");}
        authority.confirm(lease,observedGtid,UUID.randomUUID().toString());
        authority.unchanged(lease);return lease.mode();
    }
    public void close()throws SQLException {try{connection.rollback();}catch(SQLException failure){SingleDatabaseConnections.invalidate(connection);throw failure;}finally{connection.close();}}
    static Connection readOnly(Connection raw){
        return (Connection)Proxy.newProxyInstance(BoundedReadScope.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            String name=m.getName();
            if(name.equals("close"))return null;
            if(name.equals("isClosed"))return raw.isClosed();
            if(name.equals("toString"))return "Bounded SELECT-only connection";
            if(name.equals("prepareStatement")&&a.length==1&&a[0] instanceof String sql){select(sql);return statement(raw.prepareStatement(sql),sql);}
            throw new SQLException("Bounded reads forbid connection mutation or escape");
        });
    }
    private static void select(String sql)throws SQLException {
        if(!Set.of(
            "SELECT password_hash,email,generation FROM users WHERE id=? AND active=1",
            "SELECT chat_id,title,revision,updated_at FROM conversations WHERE user_id=? ORDER BY updated_at DESC,chat_id ASC LIMIT 501",
            "SELECT OCTET_LENGTH(payload) FROM conversations WHERE user_id=? AND chat_id=? AND revision=?",
            "SELECT payload FROM conversations WHERE user_id=? AND chat_id=? AND revision=?"
        ).contains(sql))throw new SQLException("Only reviewed bounded-chat SELECT statements are allowed");
    }
    private static PreparedStatement statement(PreparedStatement raw,String sql){
        return (PreparedStatement)Proxy.newProxyInstance(BoundedReadScope.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            String name=m.getName();
            if(name.equals("toString"))return "Bounded SELECT statement";
            if(name.equals("close")){raw.close();return null;}
            if(name.equals("executeQuery")&&(a==null||a.length==0))return rows(raw.executeQuery());
            if(Set.of("setLong","setInt","setString","setBytes","setNull").contains(name)){try{return m.invoke(raw,a);}catch(InvocationTargetException e){throw e.getCause();}}
            throw new SQLException("Bounded reads forbid statement mutation or escape");
        });
    }
    private static ResultSet rows(ResultSet raw){
        return (ResultSet)Proxy.newProxyInstance(BoundedReadScope.class.getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{
            String name=m.getName();
            if(Set.of("next","close","wasNull","getLong","getInt","getString","getBytes","getBoolean","getTimestamp").contains(name)){try{return m.invoke(raw,a);}catch(InvocationTargetException e){throw e.getCause();}}
            throw new SQLException("Bounded reads forbid result mutation or escape");
        });
    }
}
