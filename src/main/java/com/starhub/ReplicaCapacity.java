package com.starhub;

import java.util.Map;

/** Each participating physical database replica reserves space for each upload. */
final class ReplicaCapacity {
    private ReplicaCapacity() { }
    static Map<Integer,Long> budgets(Map<Integer,StorageCluster.Probe> probes,Map<Integer,Long> reserved,long bytes,long now) {
        if(probes==null||reserved==null||!reserved.keySet().containsAll(java.util.Set.of(1,2)))
            throw StorageFailure.unavailable();
        if(!probes.keySet().equals(java.util.Set.of(1,2))) {
            if(probes.size()!=1)throw StorageFailure.unavailable();
            var only=probes.values().iterator().next();
            if(only==null||!only.singleAuthorized||!probes.containsKey(only.node)||!java.util.Set.of(1,2).contains(only.node))throw StorageFailure.unavailable();
        }
        var result=new java.util.HashMap<Integer,Long>();
        for(int node:probes.keySet()) {
            var probe=probes.get(node);var held=reserved.get(node);
            if(probe==null||held==null||!probe.admits(bytes,held,now))
                throw new StorageFailure(507,"INSUFFICIENT_STORAGE","服务器存储空间不足，请稍后再试。");
            result.put(node,probe.budget(bytes));
        }
        return Map.copyOf(result);
    }

    static Map<Integer,Long> budgets(Map<Integer,StorageCluster.Probe> probes,Map<Integer,Long> reserved,
                                      long bytes,long now,java.util.Set<Integer> configured) {
        if(probes==null||reserved==null||configured==null||configured.size()<2
                ||probes.isEmpty()||!configured.containsAll(probes.keySet())||!reserved.keySet().containsAll(probes.keySet()))
            throw StorageFailure.unavailable();
        var result=new java.util.HashMap<Integer,Long>();
        for(int node:probes.keySet()) {
            var probe=probes.get(node);Long held=reserved.get(node);
            if(probe==null||probe.node!=node||held==null||!probe.admits(bytes,held,now))
                throw new StorageFailure(507,"INSUFFICIENT_STORAGE","服务器存储空间不足，请稍后再试。");
            result.put(node,probe.budget(bytes));
        }
        return Map.copyOf(result);
    }
}
