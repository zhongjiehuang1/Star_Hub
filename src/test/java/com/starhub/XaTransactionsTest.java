package com.starhub;

import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import javax.sql.*;
import javax.transaction.xa.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XaTransactionsTest {
    @Test void twoResourceCommitAndBusinessRollbackThroughNarayana()throws Exception{
        JdbcDataSource one=source("one"),two=source("two");
        for(JdbcDataSource source:List.of(one,two))try(Connection c=source.getConnection();Statement q=c.createStatement()){q.execute("CREATE TABLE receipts(id VARCHAR(36) PRIMARY KEY)");}
        Map<String,String> branches=new HashMap<>(),terminal=new HashMap<>();AtomicBoolean failAfterCommit=new AtomicBoolean();
        XaTransactions.Journal journal=new XaTransactions.Journal(){
            public void uid(String operation,String uid){}
            public void started(String operation,int node,Xid xid){branches.put(key(node,xid),operation);}
            public void ended(int node,Xid xid,String result)throws Exception{if(node==2&&result.equals("COMMITTED")&&failAfterCommit.compareAndSet(true,false))throw new SQLException("injected journal outage","40001");terminal.put(key(node,xid),result);}
            public boolean committed(int node,Xid xid)throws Exception{return contains(node==1?one:two,branches.get(key(node,xid)));}
        };
        try(XaTransactions transactions=new XaTransactions(Map.of(1,misidentifying(one),2,misidentifying(two)),journal,Files.createTempDirectory("starhub-xa-test-"),"starhub-test")){
            String committed=UUID.randomUUID().toString();
            assertEquals(XaTransactions.Outcome.COMMITTED,transactions.execute(committed,Set.of(1,2),connections->insert(connections,committed)));
            assertTrue(contains(one,committed));assertTrue(contains(two,committed));
            assertEquals(2,branches.size(),"Distinct logical nodes must remain separate even if the driver says same RM");
            String aborted=UUID.randomUUID().toString();
            assertEquals(XaTransactions.Outcome.ABORTED,transactions.execute(aborted,Set.of(1,2),connections->{insert(connections,aborted);throw new SQLException("injected business failure");}));
            assertFalse(contains(one,aborted));assertFalse(contains(two,aborted));
            assertEquals(2,terminal.values().stream().filter("COMMITTED"::equals).count());
            assertEquals(2,terminal.values().stream().filter("ABORTED"::equals).count());
            String retried=UUID.randomUUID().toString();failAfterCommit.set(true);
            assertNotEquals(XaTransactions.Outcome.ABORTED,transactions.execute(retried,Set.of(1,2),connections->insert(connections,retried)));
            assertTrue(contains(one,retried));assertTrue(contains(two,retried));
            for(int attempt=0;attempt<5&&transactions.hasOutstandingDecisions();attempt++)transactions.recover();
            assertFalse(transactions.hasOutstandingDecisions(),"A journal outage after a real commit must remain recoverable");
            // Crash/phase-two recovery is exercised by MySqlStorageValidation with two real MySQL
            // schemas. H2 discards its prepared transaction when that XAConnection is closed.
        }
    }
    static JdbcDataSource source(String suffix){JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:xa_"+UUID.randomUUID()+suffix+";DB_CLOSE_DELAY=-1");return source;}
    static String key(int node,Xid xid){return node+":"+xid.getFormatId()+":"+Base64.getEncoder().encodeToString(xid.getGlobalTransactionId())+":"+Base64.getEncoder().encodeToString(xid.getBranchQualifier());}
    static boolean contains(JdbcDataSource source,String id)throws SQLException{try(Connection c=source.getConnection();PreparedStatement q=c.prepareStatement("SELECT id FROM receipts WHERE id=?")){q.setString(1,id);try(ResultSet r=q.executeQuery()){return r.next();}}}
    static void insert(Map<Integer,Connection> connections,String id)throws SQLException{for(Connection c:connections.values())try(PreparedStatement q=c.prepareStatement("INSERT INTO receipts(id) VALUES(?)")){q.setString(1,id);q.executeUpdate();}}
    static XADataSource misidentifying(JdbcDataSource source){
        return new XADataSource(){
            public XAConnection getXAConnection()throws SQLException{return wrap(source.getXAConnection());}
            public XAConnection getXAConnection(String user,String password)throws SQLException{return getXAConnection();}
            private XAConnection wrap(XAConnection connection)throws SQLException{
                XAResource delegate=connection.getXAResource();
                return new XAConnection(){
                    public Connection getConnection()throws SQLException{return connection.getConnection();}
                    public XAResource getXAResource(){return new XAResource(){
                        public void start(Xid x,int f)throws XAException{delegate.start(x,f);}public void end(Xid x,int f)throws XAException{delegate.end(x,f);}
                        public int prepare(Xid x)throws XAException{return delegate.prepare(x);}public void rollback(Xid x)throws XAException{delegate.rollback(x);}
                        public void commit(Xid x,boolean one)throws XAException{delegate.commit(x,one);}
                        public void forget(Xid x)throws XAException{delegate.forget(x);}public Xid[] recover(int f)throws XAException{return delegate.recover(f);}
                        public boolean isSameRM(XAResource resource)throws XAException{return true; /* Simulate a driver that mistakes loopback tunnels for one RM. */}
                        public int getTransactionTimeout()throws XAException{return delegate.getTransactionTimeout();}public boolean setTransactionTimeout(int s)throws XAException{return delegate.setTransactionTimeout(s);}
                    };}
                    public void close()throws SQLException{connection.close();}public void addConnectionEventListener(ConnectionEventListener l){connection.addConnectionEventListener(l);}public void removeConnectionEventListener(ConnectionEventListener l){connection.removeConnectionEventListener(l);}public void addStatementEventListener(StatementEventListener l){connection.addStatementEventListener(l);}public void removeStatementEventListener(StatementEventListener l){connection.removeStatementEventListener(l);}
                };
            }
            public java.io.PrintWriter getLogWriter(){return null;}public void setLogWriter(java.io.PrintWriter ignored){}public void setLoginTimeout(int ignored){}public int getLoginTimeout(){return 0;}public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
        };
    }
}
