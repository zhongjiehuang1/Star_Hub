package com.starhub;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SingleDatabaseMigrationTest {
    private Connection database(long pending)throws Exception {
        Connection c=mock(Connection.class);when(c.getAutoCommit()).thenReturn(true);
        Statement q=mock(Statement.class);when(c.createStatement()).thenReturn(q);
        ResultSet identity=SingleDatabaseTest.identity(SingleDatabaseTest.ID,0,1,1,1,332);
        ResultSet gate=mock(ResultSet.class);when(gate.next()).thenReturn(true);when(gate.getInt(1)).thenReturn(5);
        ResultSet count=mock(ResultSet.class);when(count.next()).thenReturn(true);when(count.getLong(1)).thenReturn(pending);
        ResultSet rows=mock(ResultSet.class);when(rows.next()).thenReturn(true,true,false);when(rows.getInt(1)).thenReturn(1,2);when(rows.getLong(2)).thenReturn(0L,0L);
        when(q.executeQuery(contains("@@server_uuid"))).thenReturn(identity);
        when(q.executeQuery(contains("protocol_version"))).thenReturn(gate);
        when(q.executeQuery(contains("COUNT(*)"))).thenReturn(count);
        when(q.executeQuery(contains("ORDER BY node_id"))).thenReturn(rows);
        PreparedStatement remove=mock(PreparedStatement.class);when(c.prepareStatement(contains("DELETE FROM starhub_capacity_totals"))).thenReturn(remove);when(remove.executeUpdate()).thenReturn(1);
        return c;
    }
    @Test void refusesPendingWorkWithoutDeletingAnything()throws Exception {
        Connection c=database(1);assertThrows(SQLException.class,()->SingleDatabaseMigration.migrateCapacity(c,SingleDatabaseTest.config()));
        verify(c).rollback();verify(c,never()).commit();verify(c,never()).prepareStatement(contains("DELETE"));
    }
    @Test void removesOnlyEmptyObsoleteAccountingRowAfterFullPreflight()throws Exception {
        Connection c=database(0);SingleDatabaseMigration.migrateCapacity(c,SingleDatabaseTest.config());
        verify(c).prepareStatement("DELETE FROM starhub_capacity_totals WHERE node_id=1 AND reserved_bytes=0");verify(c).commit();verify(c,never()).rollback();
    }
}
