package com.starhub;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class ReplicaCapacityTest {
    private static StorageCluster.Probe probe(int node,long available) {
        return new StorageCluster.Probe(node,100_000,available,4,64,true);
    }
    @Test void requiresAndReservesCapacityOnBothPhysicalReplicas() {
        long floor=StorageCluster.FLOOR;
        var probes=Map.of(1,probe(1,floor+1000),2,probe(2,floor+1000));
        assertEquals(Map.of(1,464L,2,464L),ReplicaCapacity.budgets(probes,Map.of(1,0L,2,0L),100,100_000));
        assertThrows(StorageFailure.class,()->ReplicaCapacity.budgets(probes,Map.of(1,0L,2,600L),100,100_000));
        assertThrows(StorageFailure.class,()->ReplicaCapacity.budgets(Map.of(1,probes.get(1)),Map.of(1,0L,2,0L),100,100_000));
    }
    @Test void rejectsStaleAndUnprotectedCapacity() {
        long floor=StorageCluster.FLOOR;
        var probes=Map.of(1,probe(1,floor+1000),2,new StorageCluster.Probe(2,100_000,floor+1000,4,64,false));
        assertThrows(StorageFailure.class,()->ReplicaCapacity.budgets(probes,Map.of(1,0L,2,0L),100,100_000));
        assertThrows(StorageFailure.class,()->ReplicaCapacity.budgets(Map.of(1,probe(1,floor+1000),2,probe(2,floor+1000)),Map.of(1,0L,2,0L),100,110_000));
    }
    @Test void singleNodeRequiresExplicitLocalFenceAuthorization() {
        long available=StorageCluster.FLOOR+1000;
        var ordinary=probe(1,available);
        assertThrows(StorageFailure.class,()->ReplicaCapacity.budgets(Map.of(1,ordinary),Map.of(1,0L,2,0L),100,100_000));
        var fenced=new StorageCluster.Probe(1,100_000,available,4,64,true,true);
        assertEquals(Map.of(1,464L),ReplicaCapacity.budgets(Map.of(1,fenced),Map.of(1,0L,2,0L),100,100_000));
    }
    @Test void reservesEveryPhysicalReplicaWithoutEquatingItToLogicalShards(){
        long available=StorageCluster.FLOOR+1000;
        var configured=java.util.Set.of(1,2,3);var held=Map.of(1,0L,2,0L,3,0L);
        var probes=Map.of(1,probe(1,available),2,probe(2,available),3,probe(3,available));
        assertEquals(Map.of(1,464L,2,464L,3,464L),ReplicaCapacity.budgets(probes,held,100,100_000,configured));
        assertEquals(Map.of(1,464L,3,464L),ReplicaCapacity.budgets(Map.of(1,probes.get(1),3,probes.get(3)),held,100,100_000,configured));
        assertThrows(StorageFailure.class,()->ReplicaCapacity.budgets(probes,Map.of(1,0L,2,0L),100,100_000,configured));
        assertThrows(StorageFailure.class,()->ReplicaCapacity.budgets(Map.of(3,probe(2,available)),held,100,100_000,configured));
    }
}
