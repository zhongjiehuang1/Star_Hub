package com.starhub;

import java.lang.reflect.*;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct, never-reused connections with a fail-closed per-JVM admission ceiling. */
final class SingleDatabaseConnections {
    interface Opener { Connection open() throws SQLException; }
    private static final Semaphore PERMITS=new Semaphore(4,true);
    private static final java.util.concurrent.locks.ReentrantLock POOL_LOCK=new java.util.concurrent.locks.ReentrantLock();
    private static volatile SingleDatabasePool pool;
    private static final java.util.concurrent.atomic.AtomicInteger NONSINGLE_IN_FLIGHT=new java.util.concurrent.atomic.AtomicInteger();
    private static Properties poolRouting;
    private static Properties boundRouting;
    private static Boolean boundPoolMode;
    private static boolean nonSingleStarted;
    private static volatile boolean terminallyClosed;
    private static volatile boolean shutdownUnconfirmed;
    private SingleDatabaseConnections() {}
    private static synchronized void bindNonSingle()throws SQLException {
        if(terminallyClosed)throw new SQLException("Database access is closed; restart required");
        if(boundPoolMode!=null)throw new SQLException("Database topology changed; restart required");
        nonSingleStarted=true;NONSINGLE_IN_FLIGHT.incrementAndGet();
    }
    private static synchronized void bind(Properties config,boolean pooling)throws SQLException {
        if(terminallyClosed)throw new SQLException("Database access is closed; restart required");
        if(nonSingleStarted)throw new SQLException("Database topology changed; restart required");
        if(boundPoolMode==null){boundPoolMode=pooling;boundRouting=ReplicaAuthority.checkedRouting(config);return;}
        if(boundPoolMode!=pooling)throw new SQLException("Database connection mode changed; restart required");
        ReplicaAuthority.sameRouting(boundRouting,config);
    }
    /** Startup-only, read-only pool preparation. Normal request admission remains 500 ms. */
    static void warmup(Properties config,Opener checkedOpen)throws SQLException {warmup(config,checkedOpen,25_000,4_000);}
    static void warmup(Properties config,Opener checkedOpen,long prepareMillis,long closeMillis)throws SQLException {
        String flag=config.getProperty("storage.single.pool.enabled","false");
        if(!"true".equals(flag))return;
        if(!SingleDatabase.enabled(config)||prepareMillis<=0||closeMillis<=0)throw new SQLException("Invalid single database pool warmup");
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"starhub-pool-startup");t.setDaemon(true);return t;});
        java.util.concurrent.Future<?> pending=worker.submit(()->{
            java.util.List<Connection> held=new java.util.ArrayList<>();SQLException failure=null;
            try {
                while(held.size()<4){
                    if(Thread.currentThread().isInterrupted())throw new SQLTransientConnectionException("Pool warmup interrupted");
                    try{Connection c=checkedOpen.open();if(c==null)throw new SQLException("Pool warmup returned no connection");held.add(c);}
                    catch(SQLTransientConnectionException transientFailure){try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLTransientConnectionException("Pool warmup interrupted",interrupted);}}
                }
            }catch(SQLException e){failure=e;throw new java.util.concurrent.CompletionException(e);}
            finally{
                for(Connection c:held)try{c.close();}catch(SQLException close){if(failure==null)failure=close;else if(failure!=close)failure.addSuppressed(close);}
                if(failure!=null&&held.size()==4)throw new java.util.concurrent.CompletionException(failure);
            }
        });
        try {pending.get(prepareMillis,TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){boolean closed=stopWarmup(pending,worker,closeMillis);Thread.currentThread().interrupt();throw new SQLTransientConnectionException(closed?"Pool warmup interrupted; pool closed":"Pool warmup interrupted; pool shutdown unconfirmed",interrupted);}
        catch(java.util.concurrent.TimeoutException timedOut){boolean closed=stopWarmup(pending,worker,closeMillis);throw new SQLTransientConnectionException(closed?"Pool warmup timed out; pool closed":"Pool warmup timed out; pool shutdown unconfirmed",timedOut);}
        catch(java.util.concurrent.ExecutionException failed){boolean closed=stopWarmup(pending,worker,closeMillis);Throwable cause=failed.getCause();if(cause instanceof java.util.concurrent.CompletionException&&cause.getCause()!=null)cause=cause.getCause();throw new SQLException(closed?"Pool warmup failed; pool closed":"Pool warmup failed; pool shutdown unconfirmed",cause);}
        finally{worker.shutdownNow();}
    }
    private static boolean stopWarmup(java.util.concurrent.Future<?> pending,java.util.concurrent.ExecutorService worker,long millis) {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);pending.cancel(true);worker.shutdownNow();
        boolean closed=closePoolWithin(millis);long remaining=deadline-System.nanoTime();
        boolean stopped=false;try{stopped=closed&&remaining>0&&worker.awaitTermination(remaining,TimeUnit.NANOSECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        if(!stopped)markShutdownUnconfirmed();return stopped;
    }
    private static synchronized void markShutdownUnconfirmed(){shutdownUnconfirmed=true;terminallyClosed=true;}
    /** Called only by the lifecycle initializer after the previous initializer has stopped. */
    static int closePoolAfterFailure(boolean mayRetry) {
        synchronized(SingleDatabaseConnections.class){if(!Boolean.TRUE.equals(boundPoolMode))return 0;if(shutdownUnconfirmed)return 2;}
        if(!closePoolWithin(4_000)){markShutdownUnconfirmed();return 2;}
        synchronized(SingleDatabaseConnections.class){if(mayRetry&&!shutdownUnconfirmed)terminallyClosed=false;}
        return 1;
    }
    /** Fence a closing webapp even if its initializer has not bound a mode yet. */
    static int terminateForShutdown() {
        synchronized(SingleDatabaseConnections.class){terminallyClosed=true;if(shutdownUnconfirmed)return 2;}
        if(!closePoolWithin(4_000)){markShutdownUnconfirmed();return 2;}return 1;
    }
    private static boolean closePoolWithin(long millis) {
        terminallyClosed=true;long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);SingleDatabasePool toClose;
        try {if(!POOL_LOCK.tryLock(millis,TimeUnit.MILLISECONDS))return false;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
        try {toClose=pool;pool=null;}finally{POOL_LOCK.unlock();}
        if(toClose!=null){
            long remaining=deadline-System.nanoTime();
            java.util.concurrent.ExecutorService closer=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"starhub-pool-shutdown");t.setDaemon(true);return t;});
            java.util.concurrent.Future<?> stopped=closer.submit(toClose::close);
            try{if(remaining<=0)return false;stopped.get(remaining,TimeUnit.NANOSECONDS);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
            catch(Exception notClosed){return false;}finally{closer.shutdown();}
        }
        try {
            while(PERMITS.availablePermits()!=4||NONSINGLE_IN_FLIGHT.get()!=0){long remaining=deadline-System.nanoTime();if(remaining<=0)return false;TimeUnit.NANOSECONDS.sleep(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(20)));}
            return true;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
    }
    private static String catalog(String url)throws SQLException {
        try {java.net.URI uri=java.net.URI.create(url.substring(5));String path=uri.getPath();if(path==null||!path.matches("/[A-Za-z0-9_]+"))throw new IllegalArgumentException();return path.substring(1);}
        catch(RuntimeException e){throw new SQLException("Invalid pool catalog",e);}
    }
    private static String endpointAndOptions(String url)throws SQLException {
        try {java.net.URI u=java.net.URI.create(url.substring(5));if(!"mysql".equals(u.getScheme())||u.getRawUserInfo()!=null||u.getRawFragment()!=null||u.getHost()==null||u.getPort()<1)throw new IllegalArgumentException();return u.getScheme()+"://"+u.getHost()+":"+u.getPort()+"?"+java.util.Objects.toString(u.getRawQuery(),"");}
        catch(RuntimeException e){throw new SQLException("Invalid pool endpoint",e);}
    }
    private static SingleDatabasePool pooled(Properties p,Opener opener)throws SQLException {
        if(terminallyClosed)throw new SQLException("Database pool is closed; restart required");
        SingleDatabasePool existing=pool;if(existing!=null)return existing;
        if(!POOL_LOCK.tryLock())throw new SQLTransientConnectionException("Pool initialization is busy");
        try {
        if(terminallyClosed)throw new SQLException("Database pool is closed; restart required");
        SingleDatabase.validate(p);String[] prefixes={"db.","storage.node2.db.","storage.objectStore.jdbc."};String endpoint=null,user=null,password=null;java.util.Set<String> names=new java.util.HashSet<>();String baseline=null;
        for(String prefix:prefixes){String url=AccountServlet.jdbcUrl(p,prefix+"url"),u=p.getProperty(prefix+"user"),pw=p.getProperty(prefix+"password"),e=endpointAndOptions(url),schema=catalog(url);
            if(u==null||pw==null||u.isBlank()||pw.isBlank()||endpoint!=null&&(!endpoint.equals(e)||!user.equals(u)||!password.equals(pw)))throw new SQLException("Pool routes or credentials differ");
            if(endpoint==null){endpoint=e;user=u;password=pw;baseline=schema;}names.add(schema);
        }
        if(names.size()!=3)throw new SQLException("Distinct reviewed schemas required");
        if(poolRouting!=null)ReplicaAuthority.sameRouting(poolRouting,p);
        if(pool==null){
            if(poolRouting==null)poolRouting=ReplicaAuthority.checkedRouting(p);
            pool=new SingleDatabasePool(opener::open,baseline,names);
            if(terminallyClosed){SingleDatabasePool created=pool;pool=null;created.close();throw new SQLException("Pool closed during initialization");}
        }
        return pool;
        }finally{POOL_LOCK.unlock();}
    }
    static void fenceForShutdown(){terminallyClosed=true;}
    static void closePool()throws SQLException {if(!closePoolWithin(4_000)){markShutdownUnconfirmed();throw new SQLException("Pool shutdown unconfirmed");}}
    static void invalidate(Connection c){SingleDatabasePool.invalidate(c);}
    static Connection open(Properties config,String urlKey,Opener opener)throws SQLException {
        String flag=config.getProperty("storage.single.pool.enabled","false");
        if(!java.util.Set.of("true","false").contains(flag))throw new SQLException("Invalid pool setting");
        if("true".equals(flag)){
            if(!SingleDatabase.enabled(config)||!java.util.Set.of("db.url","storage.node2.db.url","storage.objectStore.jdbc.url").contains(urlKey))throw new SQLException("Pool requires reviewed single-database route");
            bind(config,true);SingleDatabasePool selected=pooled(config,opener);
            if(terminallyClosed)throw new SQLException("Database access is closed; restart required");
            Connection connection=selected.open(catalog(AccountServlet.jdbcUrl(config,urlKey)));
            if(terminallyClosed){invalidate(connection);connection.close();throw new SQLException("Database access closed during acquisition");}
            return connection;
        }
        return open(config,opener);
    }
    /** Retain legacy non-single connection semantics while tracking shutdown ownership. */
    private static Connection nonSingleOpen(Opener opener)throws SQLException {
        Connection raw;
        try{raw=opener.open();}catch(SQLException|RuntimeException|Error failure){NONSINGLE_IN_FLIGHT.decrementAndGet();throw failure;}
        if(raw==null){NONSINGLE_IN_FLIGHT.decrementAndGet();throw new SQLException("Database driver returned no connection");}
        if(terminallyClosed){try{raw.close();NONSINGLE_IN_FLIGHT.decrementAndGet();}
            catch(SQLException failure){markShutdownUnconfirmed();try{if(raw.isClosed())NONSINGLE_IN_FLIGHT.decrementAndGet();}catch(SQLException ignored){}throw failure;}
            throw new SQLException("Database access closed during acquisition");}
        AtomicBoolean released=new AtomicBoolean();
        return (Connection)Proxy.newProxyInstance(SingleDatabaseConnections.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("close")){
                try{raw.close();if(released.compareAndSet(false,true))NONSINGLE_IN_FLIGHT.decrementAndGet();}
                catch(SQLException failure){try{if(raw.isClosed()&&released.compareAndSet(false,true))NONSINGLE_IN_FLIGHT.decrementAndGet();}catch(SQLException ignored){}throw failure;}
                return null;
            }
            try{return method.invoke(raw,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }
    static Connection open(Properties config,Opener opener)throws SQLException {
        if(!SingleDatabase.enabled(config)){bindNonSingle();return nonSingleOpen(opener);}
        String flag=config.getProperty("storage.single.pool.enabled","false");
        if(!"false".equals(flag))throw new SQLException("Direct connection requires the disabled pool mode");
        bind(config,false);
        try {if(!PERMITS.tryAcquire(500,TimeUnit.MILLISECONDS)){OperationalDiagnostics.record(OperationalDiagnostics.Reason.DB_ADMISSION_TIMEOUT);throw new SQLTransientConnectionException("Database is busy; retry later","08001");}}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLTransientConnectionException("Interrupted waiting for database capacity","08001",interrupted);}
        if(terminallyClosed){PERMITS.release();throw new SQLException("Database access is closed; restart required");}
        Connection raw;
        try {raw=opener.open();}catch(SQLException|RuntimeException|Error failure){PERMITS.release();OperationalDiagnostics.record(OperationalDiagnostics.Reason.DB_OPEN_FAILURE);throw failure;}
        if(raw==null){PERMITS.release();throw new SQLException("Database driver returned no connection");}
        if(terminallyClosed){
            try{raw.close();PERMITS.release();}
            catch(SQLException failure){markShutdownUnconfirmed();try{if(raw.isClosed())PERMITS.release();}catch(SQLException ignored){}throw failure;}
            throw new SQLException("Database access closed during acquisition");
        }
        AtomicBoolean released=new AtomicBoolean();
        return (Connection)Proxy.newProxyInstance(SingleDatabaseConnections.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("unwrap")||name.equals("isWrapperFor"))throw new SQLException("Unwrapping a guarded connection is forbidden");
            if(name.equals("close")){
                try {raw.close();if(released.compareAndSet(false,true))PERMITS.release();}
                catch(SQLException failure){try{if(raw.isClosed()&&released.compareAndSet(false,true))PERMITS.release();}catch(SQLException ignored){}throw failure;}
                return null;
            }
            try{return method.invoke(raw,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }
}
