package com.starhub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SingleDatabaseTest {
    static final String ID="11111111-2222-4333-8444-555555555555";
    static Properties config(){
        Properties p=new Properties();p.setProperty("storage.topology","single-database");p.setProperty("storage.replicated","true");p.setProperty("storage.transactionMode","atomic");
        p.setProperty("storage.mysqlUuid",ID);p.setProperty("storage.single.mysqlPort","332");p.setProperty("storage.single.transport","verified-ssh-tunnel");p.setProperty("storage.physicalNodeIds","2");p.setProperty("server.id","1");
        p.setProperty("db.url","jdbc:mysql://127.0.0.1:332/a?sslMode=VERIFY_IDENTITY");p.setProperty("storage.node2.db.url","jdbc:mysql://127.0.0.1:332/b?sslMode=VERIFY_IDENTITY");p.setProperty("storage.objectStore.jdbc.url","jdbc:mysql://127.0.0.1:332/decisions?sslMode=VERIFY_IDENTITY");p.setProperty("storage.idempotency.secret","synthetic-test-identity-32-characters-long");return p;
    }
    static ResultSet identity(String uuid,int readOnly,int flush,int sync,int logBin,int port)throws Exception {
        ResultSet r=mock(ResultSet.class);when(r.next()).thenReturn(true);when(r.getString(1)).thenReturn(uuid);when(r.getString(7)).thenReturn("a");
        when(r.getInt(2)).thenReturn(readOnly);when(r.getInt(3)).thenReturn(0);when(r.getInt(4)).thenReturn(flush);when(r.getInt(5)).thenReturn(sync);when(r.getInt(6)).thenReturn(logBin);when(r.getInt(8)).thenReturn(port);when(r.getInt(9)).thenReturn(1);return r;
    }
    static Connection connection(ResultSet identity)throws Exception {
        Connection c=mock(Connection.class);Statement statement=mock(Statement.class);when(c.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(identity);
        PreparedStatement gate=mock(PreparedStatement.class);when(c.prepareStatement(contains("UPDATE file_storage_atomic_gate"))).thenReturn(gate);when(gate.executeUpdate()).thenReturn(1);return c;
    }
    @Test void explicitPinnedRoutesRequireVerifiedTlsAndCorrectPhysicalIdentity()throws Exception {
        Properties p=config();SingleDatabase.validate(p);var marker=SingleDatabase.marker(p);assertEquals("single",marker.mode());assertEquals(ID,marker.mysqlUuid());
        Properties bad=(Properties)p.clone();bad.setProperty("db.url","jdbc:mysql://127.0.0.1:332/a?sslMode=DISABLED");Properties noTls=bad;assertThrows(SQLException.class,()->SingleDatabase.validate(noTls));
        bad=(Properties)p.clone();bad.setProperty("storage.single.transport","local");Properties wrongTransport=bad;assertThrows(SQLException.class,()->SingleDatabase.validate(wrongTransport));
        bad=(Properties)p.clone();bad.setProperty("storage.physicalNodeIds","1,2");Properties wrongNodes=bad;assertThrows(SQLException.class,()->SingleDatabase.validate(wrongNodes));
        bad=(Properties)p.clone();bad.setProperty("storage.node2.db.url","jdbc:mysql://127.0.0.1:332/b?sslMode=VERIFY_IDENTITY&useSSL=false");Properties contradictory=bad;assertThrows(SQLException.class,()->SingleDatabase.validate(contradictory));
        SingleDatabase.verify(connection(identity(ID,0,1,1,1,332)),p);
        for(ResultSet wrong:List.of(identity("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",0,1,1,1,332),identity(ID,1,1,1,1,332),identity(ID,0,2,1,1,332),identity(ID,0,1,0,1,332),identity(ID,0,1,1,0,332),identity(ID,0,1,1,1,333)))assertThrows(SQLException.class,()->SingleDatabase.verify(connection(wrong),p));
    }
    @Test void freshCapacityObservationMustBeThePinnedDatabaseDisk()throws Exception {
        Properties p=config();long now=System.currentTimeMillis();ObjectNode v=ModelGateway.JSON.createObjectNode().put("nodeId",2).put("mysqlUuid",ID).put("topology","single-database").put("mode","single").put("observedAtMillis",now).put("reserveGuaranteed",true).put("capacityModelValidated",true).put("floorBytes",4_000_000_000L).put("globalReserveEnforcement",false).put("physicalBytesPerPayloadByte",6).put("fixedOverheadBytes",30_044_160L);
        v.putArray("volumes").addObject().put("id","root").put("availableBytes",8_000_000_000L);
        var result=StorageCluster.parseSingle(v,p,now);assertEquals(2,result.node);assertTrue(result.admits(1000,0,now));
        for(ObjectNode bad:List.of(v.deepCopy().put("nodeId",1),v.deepCopy().put("mysqlUuid","aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),v.deepCopy().put("mode","dual"),v.deepCopy().put("reserveGuaranteed",false),v.deepCopy().put("capacityModelValidated",false),v.deepCopy().put("floorBytes",3_999_999_999L),v.deepCopy().put("physicalBytesPerPayloadByte",5),v.deepCopy().put("observedAtMillis",now-5001)))assertThrows(StorageFailure.class,()->StorageCluster.parseSingle(bad,p,now));
    }
    @Test void uncertainCommitNeverRetriesEvenWhenDriverUsesRollbackException()throws Exception {
        Properties p=config();Connection c=connection(identity(ID,0,1,1,1,332));doThrow(new SQLTransactionRollbackException("synthetic","40001")).when(c).commit();AtomicInteger openings=new AtomicInteger(),works=new AtomicInteger();
        var marker=SingleDatabase.marker(p);var authority=new AtomicDatabase.Authority(){public ReplicaAuthority.Lease acquire(){return marker;}public void unchanged(ReplicaAuthority.Lease ignored){}public void confirm(ReplicaAuthority.Lease ignored,String gtid,String op){fail("No replica confirmation in single mode");}};
        var db=new AtomicDatabase(p,()->{openings.incrementAndGet();return c;},authority);
        assertThrows(SQLException.class,()->db.run(UUID.randomUUID().toString(),x->{works.incrementAndGet();return 1;}));assertEquals(1,works.get());assertEquals(1,openings.get());verify(c,times(1)).commit();
    }
    @Test void knownRollbackBeforeCommitMayRetryWithSameOperation()throws Exception {
        Properties p=config();Connection c=connection(identity(ID,0,1,1,1,332));var marker=SingleDatabase.marker(p);
        var authority=new AtomicDatabase.Authority(){public ReplicaAuthority.Lease acquire(){return marker;}public void unchanged(ReplicaAuthority.Lease ignored){}public void confirm(ReplicaAuthority.Lease ignored,String gtid,String op){fail("No replica confirmation in single mode");}};
        var db=new AtomicDatabase(p,()->c,authority);AtomicInteger works=new AtomicInteger();String op=UUID.randomUUID().toString();
        assertEquals(Integer.valueOf(7),db.run(op,x->{if(works.getAndIncrement()==0)throw new SQLTransactionRollbackException("synthetic","40001");return 7;}));assertEquals(2,works.get());verify(c,times(1)).commit();
    }
    @Test void failedPostCommitProofEvictsAndNeverReplays()throws Exception {
        Properties p=config();Connection raw=mock(Connection.class);java.util.concurrent.atomic.AtomicBoolean invalidated=new java.util.concurrent.atomic.AtomicBoolean();
        Connection c=(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class,SingleDatabasePool.Invalidatable.class},(proxy,method,args)->{if(method.getName().equals("invalidate")){invalidated.set(true);return null;}try{return method.invoke(raw,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});
        Statement statement=mock(Statement.class);when(raw.createStatement()).thenReturn(statement);
        ResultSet good=identity(ID,0,1,1,1,332),bad=identity("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",0,1,1,1,332);when(statement.executeQuery(anyString())).thenReturn(good,bad);
        PreparedStatement gate=mock(PreparedStatement.class);when(raw.prepareStatement(contains("UPDATE file_storage_atomic_gate"))).thenReturn(gate);when(gate.executeUpdate()).thenReturn(1);
        var marker=SingleDatabase.marker(p);var authority=new AtomicDatabase.Authority(){public ReplicaAuthority.Lease acquire(){return marker;}public void unchanged(ReplicaAuthority.Lease ignored){}public void confirm(ReplicaAuthority.Lease ignored,String gtid,String op){fail("No replica confirmation");}};
        AtomicInteger works=new AtomicInteger();var db=new AtomicDatabase(p,()->c,authority);
        assertThrows(SQLException.class,()->db.run(UUID.randomUUID().toString(),x->{works.incrementAndGet();return 1;}));
        assertEquals(1,works.get());verify(raw,times(1)).commit();assertTrue(invalidated.get());
    }

    @Test void capacityRejectionsUsePreciseCategoriesWithoutChangingGuards()throws Exception {
        Properties p=config();long now=System.currentTimeMillis();ObjectNode v=ModelGateway.JSON.createObjectNode().put("nodeId",2).put("mysqlUuid",ID).put("topology","single-database").put("mode","single").put("observedAtMillis",now).put("reserveGuaranteed",true).put("capacityModelValidated",true).put("floorBytes",4_000_000_000L).put("globalReserveEnforcement",false).put("physicalBytesPerPayloadByte",6).put("fixedOverheadBytes",30_044_160L);
        v.putArray("volumes").addObject().put("id","root").put("availableBytes",8_000_000_000L);
        Map<OperationalDiagnostics.Reason,ObjectNode> failures=new LinkedHashMap<>();
        failures.put(OperationalDiagnostics.Reason.CAPACITY_PARSE_IDENTITY,v.deepCopy().put("nodeId",1));
        failures.put(OperationalDiagnostics.Reason.CAPACITY_PARSE_FRESHNESS,v.deepCopy().put("observedAtMillis",now-5001));
        failures.put(OperationalDiagnostics.Reason.CAPACITY_PARSE_RESERVE,v.deepCopy().put("reserveGuaranteed",false));
        failures.put(OperationalDiagnostics.Reason.CAPACITY_PARSE_ESTIMATE,v.deepCopy().put("physicalBytesPerPayloadByte",5));
        ObjectNode noVolumes=v.deepCopy();noVolumes.putArray("volumes");failures.put(OperationalDiagnostics.Reason.CAPACITY_PARSE_VOLUMES,noVolumes);
        for(var item:failures.entrySet()){long before=OperationalDiagnostics.count(item.getKey());assertThrows(StorageFailure.class,()->StorageCluster.parseSingle(item.getValue(),p,now));assertEquals(before+1,OperationalDiagnostics.count(item.getKey()));}
    }

}
