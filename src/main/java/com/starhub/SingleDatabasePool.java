package com.starhub;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** One bounded physical pool. Configuration and schema choice are validated before each borrow. */
final class SingleDatabasePool implements AutoCloseable {
    interface Source { Connection open() throws SQLException; }
    interface Invalidatable { void invalidate(); }
    private final HikariDataSource pool;
    private final String baselineCatalog;
    private final Set<String> catalogs;

    SingleDatabasePool(Source source,String baselineCatalog,Set<String> catalogs) {
        if(!catalogs.contains(baselineCatalog)||catalogs.size()!=3)throw new IllegalArgumentException("Three reviewed catalogs required");
        this.catalogs=Set.copyOf(catalogs);this.baselineCatalog=baselineCatalog;
        HikariConfig c=new HikariConfig();c.setPoolName("starhub-single-database");
        c.setDataSource(new DataSource(){
            public Connection getConnection()throws SQLException{return source.open();}
            public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("Explicit credentials forbidden");}
            public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter ignored){}
            public void setLoginTimeout(int ignored){}public int getLoginTimeout(){return 0;}
            public Logger getParentLogger(){return Logger.getLogger("com.starhub");}
            public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("Datasource unwrap forbidden");}
            public boolean isWrapperFor(Class<?> type){return false;}
        });
        c.setMaximumPoolSize(4);c.setMinimumIdle(1);c.setConnectionTimeout(500);c.setValidationTimeout(250);
        c.setInitializationFailTimeout(-1);c.setAutoCommit(true);c.setReadOnly(false);
        c.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");c.setCatalog(baselineCatalog);
        pool=new HikariDataSource(c);
    }
    Connection open(String catalog)throws SQLException {
        if(!catalogs.contains(catalog))throw new SQLException("Unreviewed database catalog");
        Connection raw;try{raw=pool.getConnection();}catch(SQLException unavailable){OperationalDiagnostics.record(OperationalDiagnostics.Reason.POOL_ACQUIRE_FAILED);throw unavailable;}AtomicBoolean broken=new AtomicBoolean(),closed=new AtomicBoolean();
        try {
            raw.setCatalog(catalog);
            try(Statement q=raw.createStatement();ResultSet r=q.executeQuery("SELECT DATABASE()")){
                if(!r.next()||!catalog.equals(r.getString(1)))throw new SQLException("Database catalog mismatch");
            }
        }catch(SQLException|RuntimeException failure){pool.evictConnection(raw);try{raw.close();}catch(SQLException ignored){}throw failure;}
        return (Connection)Proxy.newProxyInstance(SingleDatabasePool.class.getClassLoader(),new Class<?>[]{Connection.class,Invalidatable.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("invalidate")){broken.set(true);return null;}
            if(name.equals("unwrap")||name.equals("isWrapperFor"))throw new SQLException("Pooled connection unwrap forbidden");
            if(name.equals("close")){
                if(!closed.compareAndSet(false,true))return null;
                try {
                    if(!broken.get()){
                        if(!raw.getAutoCommit())raw.rollback();
                        raw.setReadOnly(false);raw.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                        raw.setAutoCommit(true);raw.setCatalog(baselineCatalog);raw.clearWarnings();
                        try(Statement q=raw.createStatement()){
                            q.execute("SET SESSION time_zone='+00:00'");
                            try(ResultSet r=q.executeQuery("SELECT DATABASE(),@@SESSION.sql_log_bin,@@SESSION.time_zone")){
                                if(!r.next()||!baselineCatalog.equals(r.getString(1))||r.getInt(2)!=1||!"+00:00".equals(r.getString(3)))throw new SQLException("Session reset was not confirmed");
                            }
                        }
                    }
                }catch(SQLException|RuntimeException failure){broken.set(true);OperationalDiagnostics.record(OperationalDiagnostics.Reason.POOL_RESET_FAILED);throw failure;}
                finally{if(broken.get())pool.evictConnection(raw);raw.close();}
                return null;
            }
            if(closed.get())throw new SQLException("Connection is closed");
            try{return method.invoke(raw,args);}catch(InvocationTargetException failure){
                Throwable cause=failure.getCause();
                if(name.equals("commit")||name.equals("rollback")||name.equals("abort"))broken.set(true);
                throw cause;
            }
        });
    }
    static void invalidate(Connection connection){if(connection instanceof Invalidatable p)p.invalidate();}
    @Override public void close(){pool.close();}
}
