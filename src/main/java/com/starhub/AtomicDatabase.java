package com.starhub;

import java.lang.reflect.*;
import java.net.URI;
import java.sql.*;
import java.util.*;

/** One InnoDB commit owns every logical schema and its result. No XA presumed-abort recovery exists here. */
final class AtomicDatabase {
    static final int MAX_CHUNK_TRANSACTION_BYTES=3*1024*1024;
    static final long MAX_COMPETING_TRANSACTION_BYTES=Math.max(MAX_CHUNK_TRANSACTION_BYTES,
        2*(WorkspaceMutationBudget.MAX_CHAT_UTF8_BYTES+WorkspaceMutationBudget.MAX_TITLE_UTF8_BYTES)+AtomicHttp.MAX_MUTATION_RECEIPT_ROW_BYTES);
    @FunctionalInterface interface Connections { Connection open()throws Exception; }
    @FunctionalInterface interface Work<T> { T run(Connection connection)throws Exception; }
    interface Authority {
        ReplicaAuthority.Lease acquire()throws SQLException;
        void unchanged(ReplicaAuthority.Lease lease)throws SQLException;
        void confirm(ReplicaAuthority.Lease lease,String gtidSet,String operation)throws SQLException;
    }
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    record Confirmation(String operation,ReplicaAuthority.Lease lease){}
    private static final ThreadLocal<Confirmation> CONFIRMED=new ThreadLocal<>();
    static void clearConfirmation(){CONFIRMED.remove();}
    static Confirmation confirmation(){return CONFIRMED.get();}
    final Properties config;final String primarySchema,blobSchema;
    private final Connections connections;private final Authority authority;
    private final java.util.concurrent.locks.ReentrantLock writer=new java.util.concurrent.locks.ReentrantLock(true);
    private volatile boolean closing;

    AtomicDatabase(Properties config)throws Exception {
        this(config,()->AccountServlet.unscopedDb(config),new Authority(){
            public ReplicaAuthority.Lease acquire()throws SQLException{return ReplicaAuthority.lease(config);}
            public void unchanged(ReplicaAuthority.Lease lease)throws SQLException{ReplicaAuthority.unchanged(config,lease);}
            public void confirm(ReplicaAuthority.Lease lease,String gtid,String operation)throws SQLException{ReplicaAuthority.confirm(config,lease,gtid,operation);}
        });
    }
    AtomicDatabase(Properties config,Connections connections,Authority authority)throws Exception {
        this.config=config;this.connections=connections;this.authority=authority;
        if("atomic".equals(config.getProperty("storage.transactionMode"))&&config.getProperty("storage.idempotency.secret","").length()<32)
            throw new SQLException("Atomic request identity secret is not configured");
        primarySchema=schema(config.getProperty("db.url"));blobSchema=schema(config.getProperty("storage.node2.db.url"));
        if(primarySchema.equalsIgnoreCase(blobSchema))throw new SQLException("Atomic logical schemas must be distinct");
    }
    private static String schema(String url)throws SQLException {
        try{URI uri=URI.create(url.substring(5));String schema=uri.getPath().substring(1);if(!schema.matches("[A-Za-z0-9_]+"))throw new IllegalArgumentException();return schema;}
        catch(Exception failure){throw new SQLException("Invalid atomic schema name");}
    }
    String table(int node,String table)throws SQLException {
        if((node!=1&&node!=2)||!table.matches("[A-Za-z0-9_]+"))throw new SQLException("Invalid logical table");
        return "`"+(node==1?primarySchema:blobSchema)+"`.`"+table+"`";
    }
    static Connection borrowed(Properties current)throws SQLException {
        Scope scope=CURRENT.get();if(scope==null)return null;
        ReplicaAuthority.sameRouting(scope.database.config,current);
        return scope.borrowed;
    }
    boolean inside(){Scope scope=CURRENT.get();return scope!=null&&scope.database==this;}
    static void claimChunkMutation(Connection connection)throws SQLException {
        claimChunkMutation(connection,ChunkedPayloadStore.CHUNK_BYTES);
    }
    static void claimChunkMutation(Connection connection,int bytes)throws SQLException {
        Scope scope=CURRENT.get();if(scope==null){if(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"))return;throw new SQLException("Payload chunks require the actual atomic authority scope");}
        if(connection!=scope.connection&&connection!=scope.borrowed)throw new SQLException("Payload chunk is outside the current atomic scope");
        if(bytes<0||bytes>ChunkedPayloadStore.CHUNK_BYTES||scope.chunkBytes+bytes>MAX_CHUNK_TRANSACTION_BYTES)throw new SQLException("Payload mutations exceed the bounded atomic transaction size");scope.chunkBytes+=bytes;
    }
    Connection current()throws SQLException {Scope scope=CURRENT.get();if(scope==null||scope.database!=this)throw new SQLException("No atomic request scope");return scope.connection;}

    Scope begin(String operation)throws Exception {
        if(closing)throw StorageFailure.unavailable();
        if(CURRENT.get()!=null)throw new SQLException("Overlapping atomic scope");
        if(!writer.tryLock())throw StorageFailure.pending();
        ReplicaAuthority.Lease lease;Connection connection;
        try{lease=authority.acquire();connection=connections.open();}catch(Exception failure){writer.unlock();throw failure;}
        try {
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            authority.unchanged(lease);
            try(PreparedStatement statement=connection.prepareStatement("UPDATE file_storage_atomic_gate SET revision=revision+1 WHERE id=1 AND protocol_version=5")){
                if(statement.executeUpdate()!=1)throw new SQLException("Atomic schema migration is required");
            }
            Scope scope=new Scope(this,connection,lease,operation);CURRENT.set(scope);return scope;
        }catch(Exception failure){try{connection.rollback();}catch(Exception ignored){}try{connection.close();}catch(Exception ignored){}writer.unlock();throw failure;}
    }
    <T>T run(String operation,Work<T> work)throws Exception {
        if(inside())return work.run(current());
        for(int attempt=0;attempt<3;attempt++){
            try(Scope scope=begin(operation)){
                T result=work.run(scope.connection);scope.commit();return result;
            }catch(SQLException failure){if(!knownRollback(failure)||attempt==2)throw failure;}
        }
        throw StorageFailure.pending();
    }
    static boolean knownRollback(SQLException failure){return failure instanceof SQLTransactionRollbackException||"40001".equals(failure.getSQLState());}
    void unchangedPermit(ReplicaAuthority.Lease lease)throws SQLException {authority.unchanged(lease);}
    /** Internal discovery only; never a public durable outcome or permission to delete. */
    <T>T readHint(Work<T> work)throws Exception {
        if(inside())return work.run(current());ReplicaAuthority.Lease lease=authority.acquire();
        try(Connection c=connections.open()){c.setReadOnly(true);T result=work.run(c);authority.unchanged(lease);return result;}
    }
    ReplicaAuthority.Lease currentLease()throws SQLException {
        Scope scope=CURRENT.get();if(scope==null||scope.database!=this)throw new SQLException("No atomic authority scope");return scope.lease;
    }
    void markReservation(String operation)throws SQLException {Scope scope=CURRENT.get();if(scope==null||scope.database!=this)throw new SQLException("Missing reservation scope");scope.reservations.add(operation);}
    boolean reservationChangedHere(String operation)throws SQLException {Scope scope=CURRENT.get();if(scope==null||scope.database!=this)throw new SQLException("Missing content scope");return scope.reservations.contains(operation);}
    boolean closeAndDrain()throws InterruptedException {
        closing=true;if(!writer.tryLock(20,java.util.concurrent.TimeUnit.SECONDS))return false;
        try{return true;}finally{writer.unlock();}
    }

    void runtimeProbe(String expectedIdentity)throws Exception {
        ReplicaAuthority.Lease lease=authority.acquire();
        try(Connection c=connections.open()){
            try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT protocol_version FROM file_storage_atomic_gate WHERE id=1")){if(!r.next()||r.getInt(1)!=5)throw new SQLException("Runtime atomic protocol is unavailable");}
            try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT store_identity FROM file_storage_coordinator WHERE id=1")){if(!r.next()||!expectedIdentity.equals(r.getString(1)))throw new SQLException("Runtime storage identity changed");}
            for(String query:List.of("SELECT id,generation,password_hash FROM users LIMIT 0","SELECT id,owner_generation,size_bytes FROM library_files LIMIT 0",
                    "SELECT user_id,generation,used_bytes,reserved_bytes FROM file_storage_users LIMIT 0",
                    "SELECT op_id,state,files_json FROM file_storage_operations LIMIT 0","SELECT operation_id,response_status FROM starhub_request_results LIMIT 0",
                    "SELECT guest_id,cookie_hash,expires_at_millis FROM starhub_guest_sessions LIMIT 0","SELECT guest_id,tab_id,value_cipher FROM starhub_guest_fields LIMIT 0",
                    "SELECT guest_id,operation_id,result_cipher FROM starhub_guest_operations LIMIT 0",
                    "SELECT operation_id,owner_hash,state,result_cipher,account_user_id,account_generation,image_budget_bytes,logical_reserved FROM starhub_provider_operations LIMIT 0",
                    "SELECT operation_id,node_id,reserved_bytes FROM starhub_provider_capacity LIMIT 0","SELECT payload_id,state,byte_count FROM starhub_payload_manifests LIMIT 0","SELECT payload_id,chunk_index,byte_count FROM starhub_payload_chunks LIMIT 0","SELECT file_id,operation_id,plan_json FROM starhub_file_payloads LIMIT 0","SELECT guest_id,refs_json FROM starhub_guest_values LIMIT 0","SELECT guest_id,refs_json FROM starhub_guest_streams LIMIT 0","SELECT cleanup_id,state FROM starhub_guest_cleanup LIMIT 0","SELECT operation_id,plan_json FROM starhub_provider_payloads LIMIT 0",
                    "SELECT used_units,operation_count FROM starhub_guest_budget WHERE id=1",
                    "SELECT guest_id,tab_id,value_cipher FROM starhub_guest_staging LIMIT 0",
                    "SELECT guest_id,tab_id,last_flush FROM starhub_guest_tabs LIMIT 0",
                    "SELECT id,content,sha256,size_bytes FROM "+table(1,"file_blobs")+" LIMIT 0","SELECT id,content,sha256,size_bytes FROM "+table(2,"file_blobs")+" LIMIT 0"))
                try(Statement q=c.createStatement()){q.executeQuery(query).close();}
            authority.unchanged(lease);
        }
    }

    void verifyDrainedSchema()throws Exception {
        ReplicaAuthority.Lease lease=authority.acquire();
        try(Connection c=connections.open()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try(PreparedStatement q=c.prepareStatement("SELECT protocol_version FROM file_storage_atomic_gate WHERE id=1");ResultSet r=q.executeQuery()){
                if(!r.next()||r.getInt(1)!=5)throw new SQLException("Atomic upload-reservation migration is not provisioned");
            }
            for(String sql:List.of("SELECT operation_id,user_id,user_generation,endpoint,request_hash,response_status,response_type,response_body FROM starhub_request_results WHERE 1=0",
                    "SELECT identity_hash,window_start_millis,failures FROM starhub_login_limits WHERE 1=0",
                    "SELECT operation_id,identity_hash,request_hash,email_hash,outcome_status FROM starhub_login_attempts WHERE 1=0",
                    "SELECT identity_hash,window_start_millis,requests FROM starhub_login_status_limits WHERE 1=0",
                    "SELECT op_id,owner_node_id,owner_mysql_uuid,owner_epoch,owner_permit,reserved_view FROM starhub_upload_reservations WHERE 1=0",
                    "SELECT op_id,node_id,reserved_bytes FROM starhub_reserved_capacity WHERE 1=0",
                    "SELECT node_id,reserved_bytes FROM starhub_capacity_totals WHERE 1=0",
                    "SELECT operation_id,owner_hash,request_hash,kind,state,dispatch_hash,reserved_view,response_status,result_cipher,account_user_id,account_generation,image_budget_bytes,logical_reserved FROM starhub_provider_operations WHERE 1=0",
                    "SELECT operation_id,node_id,reserved_bytes FROM starhub_provider_capacity WHERE 1=0",
                    "SELECT guest_id,cookie_hash,used_units,operation_count,expires_at_millis FROM starhub_guest_sessions WHERE 1=0",
                    "SELECT guest_id,tab_id,last_flush FROM starhub_guest_tabs WHERE 1=0",
                    "SELECT guest_id,tab_id,field_name,version,transaction_id,value_cipher,size_units FROM starhub_guest_fields WHERE 1=0",
                    "SELECT guest_id,tab_id,field_name,version,transaction_id,next_chunk,value_cipher,size_units FROM starhub_guest_staging WHERE 1=0",
                    "SELECT guest_id,operation_id,request_hash,result_cipher FROM starhub_guest_operations WHERE 1=0",
                    "SELECT used_units,operation_count FROM starhub_guest_budget WHERE id=1"))
                try(Statement q=c.createStatement()){q.executeQuery(sql).close();}
            for(String sql:List.of("SELECT COUNT(*) FROM file_storage_operations WHERE state NOT IN ('COMMITTED','ABORTED','RESERVED','CANCELLING','CANCELLED','DELETING')",
                    "SELECT COUNT(*) FROM file_storage_users u WHERE active_op IS NOT NULL OR reserved_bytes<>COALESCE((SELECT SUM(o.logical_bytes) FROM file_storage_operations o WHERE o.user_id=u.user_id AND o.user_generation=u.generation AND o.state IN ('RESERVED','CANCELLING')),0)+COALESCE((SELECT SUM(p.logical_reserved) FROM starhub_provider_operations p WHERE p.account_user_id=u.user_id AND p.account_generation=u.generation),0)",
                    "SELECT COUNT(*) FROM file_storage_operations o LEFT JOIN starhub_upload_reservations r ON r.op_id=o.op_id WHERE o.state IN ('RESERVED','CANCELLING') AND (o.kind<>'UPLOAD' OR r.op_id IS NULL)",
                    "SELECT COUNT(*) FROM starhub_reserved_capacity c LEFT JOIN file_storage_operations o ON o.op_id=c.op_id WHERE o.op_id IS NULL OR o.state NOT IN ('RESERVED','CANCELLING','DELETING')",
                    "SELECT COUNT(*) FROM starhub_capacity_totals t WHERE t.reserved_bytes<>COALESCE((SELECT SUM(c.reserved_bytes) FROM starhub_reserved_capacity c WHERE c.node_id=t.node_id),0)+COALESCE((SELECT SUM(c.reserved_bytes) FROM starhub_provider_capacity c WHERE c.node_id=t.node_id),0)",
                    "SELECT 1-COUNT(*) FROM starhub_guest_budget WHERE id=1",
                    "SELECT COUNT(*) FROM starhub_guest_fields f LEFT JOIN starhub_guest_sessions s ON s.guest_id=f.guest_id WHERE s.guest_id IS NULL",
                    "SELECT COUNT(*) FROM starhub_guest_staging f LEFT JOIN starhub_guest_sessions s ON s.guest_id=f.guest_id WHERE s.guest_id IS NULL",
                    "SELECT COUNT(*) FROM starhub_guest_operations f LEFT JOIN starhub_guest_sessions s ON s.guest_id=f.guest_id WHERE s.guest_id IS NULL",
                    "SELECT COUNT(*) FROM starhub_guest_tabs f LEFT JOIN starhub_guest_sessions s ON s.guest_id=f.guest_id WHERE s.guest_id IS NULL",
                    "SELECT COUNT(*) FROM starhub_guest_sessions s WHERE s.used_units<>COALESCE((SELECT SUM(f.size_units) FROM starhub_guest_values f WHERE f.guest_id=s.guest_id),0)+COALESCE((SELECT SUM(t.size_units) FROM starhub_guest_streams t WHERE t.guest_id=s.guest_id),0)+COALESCE((SELECT SUM(x.size_units) FROM starhub_guest_cleanup x WHERE x.guest_id=s.guest_id AND x.state='CANCELLING'),0)+COALESCE((SELECT SUM(f.size_units) FROM starhub_guest_fields f WHERE f.guest_id=s.guest_id AND NOT EXISTS(SELECT 1 FROM starhub_guest_values v WHERE v.guest_id=f.guest_id AND v.tab_id=f.tab_id AND v.field_name=f.field_name) AND NOT EXISTS(SELECT 1 FROM starhub_guest_cleanup x WHERE x.guest_id=f.guest_id AND x.legacy_tab=f.tab_id AND x.legacy_field=f.field_name AND x.legacy_kind='fields')),0)+COALESCE((SELECT SUM(t.size_units) FROM starhub_guest_staging t WHERE t.guest_id=s.guest_id AND NOT EXISTS(SELECT 1 FROM starhub_guest_streams v WHERE v.guest_id=t.guest_id AND v.tab_id=t.tab_id) AND NOT EXISTS(SELECT 1 FROM starhub_guest_cleanup x WHERE x.guest_id=t.guest_id AND x.legacy_tab=t.tab_id AND x.legacy_kind='staging')),0) OR s.operation_count<>(SELECT COUNT(*) FROM starhub_guest_operations o WHERE o.guest_id=s.guest_id)",
                    "SELECT COUNT(*) FROM starhub_guest_budget b WHERE b.id<>1 OR b.used_units<>COALESCE((SELECT SUM(s.used_units) FROM starhub_guest_sessions s),0) OR b.operation_count<>(SELECT COUNT(*) FROM starhub_guest_operations)",
                    "SELECT COUNT(*) FROM starhub_provider_operations WHERE state NOT IN ('CLAIMED','SENT','UNKNOWN','COMPLETED','COMPLETED_NO_RESULT')",
                    "SELECT COUNT(*) FROM starhub_provider_capacity c LEFT JOIN starhub_provider_operations o ON o.operation_id=c.operation_id WHERE o.operation_id IS NULL OR o.state NOT IN ('CLAIMED','SENT','UNKNOWN')",
                    "SELECT COUNT(*) FROM starhub_provider_operations o WHERE o.state IN ('CLAIMED','SENT','UNKNOWN') AND NOT EXISTS (SELECT 1 FROM starhub_provider_capacity c WHERE c.operation_id=o.operation_id)",
                    "SELECT COUNT(*) FROM file_storage_nodes WHERE reserved_bytes<>0",
                    "SELECT COUNT(*) FROM file_storage_branches WHERE terminal IS NULL OR terminal NOT IN ('COMMITTED','ABORTED')",
                    "SELECT COUNT(*) FROM starhub_login_attempts WHERE outcome_status NOT IN (200,401,403,429)"))
                try(Statement q=c.createStatement();ResultSet r=q.executeQuery(sql)){if(!r.next()||r.getLong(1)!=0)throw new SQLException("Legacy storage must be drained before atomic startup");}
            ChunkedRecoveryAudit.verify(c,new AtomicPayloads(this,config));
            for(String table:List.of("file_storage_physical_nodes","file_storage_replica_reservations")){
                try(ResultSet metadata=c.getMetaData().getTables(primarySchema,null,table,new String[]{"TABLE"})){
                    if(metadata.next())try(Statement q=c.createStatement();ResultSet r=q.executeQuery(table.equals("file_storage_physical_nodes")?"SELECT COUNT(*) FROM file_storage_physical_nodes WHERE reserved_bytes<>0":"SELECT COUNT(*) FROM file_storage_replica_reservations")){
                        if(!r.next()||r.getLong(1)!=0)throw new SQLException("Old physical reservations must be recovered before atomic startup");
                    }
                }
            }
            for(String schema:List.of(primarySchema,blobSchema)){
                try(ResultSet tables=c.getMetaData().getTables(schema,null,"%",new String[]{"TABLE"})){
                    while(tables.next()){
                        String table=tables.getString("TABLE_NAME");
                        try(ResultSet primary=c.getMetaData().getPrimaryKeys(schema,null,table)){if(!primary.next())throw new SQLException("Atomic storage requires explicit primary keys");}
                        try(ResultSet keys=c.getMetaData().getImportedKeys(schema,null,table)){
                        while(keys.next())if(keys.getShort("DELETE_RULE")!=DatabaseMetaData.importedKeyRestrict&&keys.getShort("DELETE_RULE")!=DatabaseMetaData.importedKeyNoAction
                                ||keys.getShort("UPDATE_RULE")!=DatabaseMetaData.importedKeyRestrict&&keys.getShort("UPDATE_RULE")!=DatabaseMetaData.importedKeyNoAction)
                            throw new SQLException("Cascading foreign keys are not accepted for atomic multi-primary storage");
                        }
                    }
                }
                try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' AND (ENGINE IS NULL OR ENGINE<>'InnoDB')")){
                    q.setString(1,schema);try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong(1)!=0)throw new SQLException("Atomic storage requires InnoDB tables");}
                }
            }
            authority.unchanged(lease);
        }
    }

    final class Scope implements AutoCloseable {
        final AtomicDatabase database;final Connection connection,borrowed;final ReplicaAuthority.Lease lease;final String operation;
        boolean rollbackOnly,committing,committed,closed;long chunkBytes;
        final Set<String> reservations=new HashSet<>();
        Scope(AtomicDatabase database,Connection connection,ReplicaAuthority.Lease lease,String operation){
            this.database=database;this.connection=connection;this.lease=lease;this.operation=operation;
            borrowed=(Connection)Proxy.newProxyInstance(AtomicDatabase.class.getClassLoader(),new Class<?>[]{Connection.class},this::invoke);
        }
        private Object invoke(Object proxy,Method method,Object[] args)throws Throwable {
            String name=method.getName();
            if(name.equals("close")||name.equals("commit"))return null;
            if(name.equals("rollback")){rollbackOnly=true;return null;}
            if(name.equals("setAutoCommit"))return null;
            if(name.equals("getAutoCommit"))return false;
            if(name.equals("isClosed"))return closed;
            if(Set.of("unwrap","setCatalog","setSchema","abort","setSavepoint","releaseSavepoint","prepareCall","nativeSQL","setTransactionIsolation","setHoldability","setTypeMap","setClientInfo","setNetworkTimeout","setReadOnly","beginRequest","endRequest").contains(name))throw new SQLException("Atomic request connection cannot escape its transaction");
            if(name.equals("isWrapperFor"))return false;
            if(name.equals("equals"))return proxy==args[0];
            if(name.equals("hashCode"))return System.identityHashCode(proxy);
            if(name.equals("toString"))return "Atomic request connection";
            if(closed)throw new SQLException("Atomic scope has closed");
            if(!Set.of("createStatement","prepareStatement","getMetaData","getWarnings","clearWarnings","isValid","isReadOnly","getCatalog","getSchema","getTransactionIsolation","getHoldability","getNetworkTimeout").contains(name))
                throw new SQLException("Unsupported operation on an atomic request connection");
            String prepared=null;if(name.equals("prepareStatement")){prepared=(String)args[0];AtomicSql.verify(prepared);}
            try {
                Object value=method.invoke(connection,args);
                if(value instanceof Statement statement)return statement(statement,prepared);
                if(value instanceof DatabaseMetaData metadata)return Proxy.newProxyInstance(AtomicDatabase.class.getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(p,m,a)->{
                    if(m.getName().equals("getConnection"))return borrowed;
                    if(m.getName().equals("unwrap"))throw new SQLException("Cannot unwrap atomic metadata");
                    try{Object result=m.invoke(metadata,a);return result instanceof ResultSet rows?rows(rows):result;}catch(InvocationTargetException e){throw e.getCause();}
                });
                return value;
            }catch(InvocationTargetException failure){throw failure.getCause();}
        }
        private Object statement(Statement target,String prepared){
            Class<?> type=target instanceof CallableStatement?CallableStatement.class:target instanceof PreparedStatement?PreparedStatement.class:Statement.class;
            return Proxy.newProxyInstance(AtomicDatabase.class.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
                if(m.getName().equals("getConnection"))return borrowed;
                if(m.getName().equals("toString"))return "Atomic request statement";
                if(m.getName().equals("unwrap"))throw new SQLException("Cannot unwrap atomic statement");
                if(m.getName().equals("isWrapperFor"))return false;
                if(m.getName().startsWith("execute")||m.getName().equals("addBatch")){
                    if(a!=null&&a.length>0&&a[0] instanceof String sql)AtomicSql.verify(sql);
                    else if(prepared!=null)AtomicSql.verify(prepared);
                    else if(!m.getName().equals("executeBatch")&&!m.getName().equals("executeLargeBatch"))throw new SQLException("Atomic SQL text unavailable");
                }
                try{Object value=m.invoke(target,a);return value instanceof ResultSet rows?rows(rows):value;}catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }
        private ResultSet rows(ResultSet target){
            return (ResultSet)Proxy.newProxyInstance(AtomicDatabase.class.getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{
                if(m.getName().equals("getStatement")){Statement value=target.getStatement();return value==null?null:statement(value,null);}
                if(m.getName().equals("toString"))return "Atomic request result set";
                if(m.getName().equals("unwrap"))throw new SQLException("Cannot unwrap atomic results");
                if(m.getName().equals("isWrapperFor"))return false;
                try{return m.invoke(target,a);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }
        void commit()throws Exception {
            if(rollbackOnly)throw new SQLException("Atomic request was rolled back");
            authority.unchanged(lease);committing=true;
            connection.commit();committed=true;
            String gtid;
            try(Statement q=connection.createStatement();ResultSet r=q.executeQuery("SELECT @@global.gtid_executed")){if(!r.next()||(gtid=r.getString(1))==null||gtid.isBlank())throw new SQLException("Durable transaction identity unavailable");}
            authority.confirm(lease,gtid,operation);
            CONFIRMED.set(new Confirmation(operation,lease));
        }
        public void close()throws SQLException {
            if(closed)return;closed=true;CURRENT.remove();
            try{if(!committed)connection.rollback();}finally{try{connection.close();}finally{writer.unlock();}}
        }
    }
}
