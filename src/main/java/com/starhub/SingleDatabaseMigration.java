package com.starhub;

import java.sql.*;
import java.util.*;

/** Explicit stopped-writers transition of capacity bookkeeping; never discards pending work. */
final class SingleDatabaseMigration {
    private SingleDatabaseMigration() {}
    /** Caller must first prove BOTH application writers stopped, preserve a backup, and select the intended DB. */
    static void migrateCapacity(Connection c,Properties config)throws SQLException {
        if(!SingleDatabase.enabled(config))throw new SQLException("Explicit single database mode is required");
        SingleDatabase.verify(c,config);
        if(!c.getAutoCommit())throw new SQLException("Migration requires a fresh connection");
        boolean committing=false;
        try {
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT protocol_version FROM file_storage_atomic_gate WHERE id=1 FOR UPDATE")) {
                if(!r.next()||r.getInt(1)!=5)throw new SQLException("Atomic gate is unavailable");
            }
            for(String sql:List.of(
                "SELECT COUNT(*) FROM file_storage_operations WHERE state IN ('RESERVED','CANCELLING','DELETING')",
                "SELECT COUNT(*) FROM starhub_provider_operations WHERE state IN ('CLAIMED','SENT','UNKNOWN')",
                "SELECT COUNT(*) FROM starhub_reserved_capacity",
                "SELECT COUNT(*) FROM starhub_provider_capacity",
                "SELECT COUNT(*) FROM file_storage_users WHERE reserved_bytes<>0 OR active_op IS NOT NULL")) {
                try(Statement q=c.createStatement();ResultSet r=q.executeQuery(sql)){if(!r.next()||r.getLong(1)!=0)throw new SQLException("Pending operations or capacity must be reconciled, never deleted");}
            }
            Map<Integer,Long> rows=new TreeMap<>();
            try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT node_id,reserved_bytes FROM starhub_capacity_totals ORDER BY node_id FOR UPDATE")){while(r.next())rows.put(r.getInt(1),r.getLong(2));}
            if(!rows.keySet().equals(Set.of(1,2))||rows.values().stream().anyMatch(x->x!=0))throw new SQLException("Expected two empty physical accounting rows");
            try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_capacity_totals WHERE node_id=1 AND reserved_bytes=0")){if(q.executeUpdate()!=1)throw new SQLException("Physical accounting row changed");}
            SingleDatabase.verify(c,config);committing=true;c.commit();
            SingleDatabase.verify(c,config);
        }catch(SQLException failure) {
            if(!committing)try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}
            else throw new SQLException("Migration commit outcome requires explicit reconciliation",failure);
            throw failure;
        }
    }
}
