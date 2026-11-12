package com.starhub;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileLibraryTest {
    @Test void pageBeyondTheOldFiveThousandFileLimitIsReachableAndAccountScoped() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement totals = mock(PreparedStatement.class), page = mock(PreparedStatement.class);
        ResultSet summary = mock(ResultSet.class), rows = mock(ResultSet.class);
        when(connection.prepareStatement(startsWith("SELECT COUNT(*)"))).thenReturn(totals);
        when(connection.prepareStatement(startsWith("SELECT id,name"))).thenReturn(page);
        when(totals.executeQuery()).thenReturn(summary); when(page.executeQuery()).thenReturn(rows);
        when(summary.next()).thenReturn(true);
        when(summary.getLong(1)).thenReturn(5001L); when(summary.getLong(2)).thenReturn(19_999_999_999L);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("oldest-file"); when(rows.getString(2)).thenReturn("old.txt");
        when(rows.getString(3)).thenReturn("text/plain"); when(rows.getLong(4)).thenReturn(6L);
        when(rows.getBoolean(5)).thenReturn(true);
        var result = FileLibrary.listPage(connection, 42,
                ModelGateway.JSON.createObjectNode().put("page", 51).put("pageSize", 100));
        assertEquals(5001, result.path("total").asLong()); assertEquals(51, result.path("page").asInt());
        assertFalse(result.path("hasNext").asBoolean()); assertEquals(2_000_000_000L, result.path("quota").asLong());
        assertEquals(19_999_999_999L, result.path("used").asLong());
        assertEquals("oldest-file", result.path("files").get(0).path("id").asText());
        assertTrue(result.path("files").get(0).path("trashed").asBoolean());
        verify(totals).setLong(1,42); verify(page).setLong(1,42); verify(page).setInt(2,100); verify(page).setLong(3,5000);
        verify(connection).prepareStatement("SELECT id,name,mime,size_bytes,deleted_at IS NOT NULL FROM library_files "
                + "WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?");
        verify(summary).close(); verify(rows).close(); verify(totals).close(); verify(page).close();
    }

    @Test void deletingTheLastPageClampsBackWithoutLosingAccessToRemainingFiles() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getLong(1)).thenReturn(100L);
        var result = FileLibrary.listPage(connection, 3, ModelGateway.JSON.createObjectNode().put("page", 2));
        assertEquals(1, result.path("page").asInt()); assertFalse(result.path("hasNext").asBoolean());
        verify(statement).setLong(3, 0);
    }

    @Test void paginationRejectsNonIntegersNegativeValuesAndUnboundedSizesBeforeQuerying() {
        for (String json : new String[]{"{\"page\":0}", "{\"page\":-1}", "{\"page\":1.5}",
                "{\"page\":\"1\"}", "{\"page\":2147483648}", "{\"pageSize\":0}", "{\"pageSize\":101}"}) {
            Connection connection = mock(Connection.class);
            assertThrows(IllegalArgumentException.class,
                    () -> FileLibrary.listPage(connection, 1, ModelGateway.JSON.readTree(json)), json);
            verifyNoInteractions(connection);
        }
    }
}
