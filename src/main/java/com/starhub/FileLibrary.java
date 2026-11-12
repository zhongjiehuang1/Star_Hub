package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Bounded account-scoped file listing. All queries use one database snapshot. */
final class FileLibrary {
    static final int PAGE_SIZE = 100;
    private FileLibrary() { }

    static int positiveInteger(JsonNode input, String field, int fallback, int maximum) {
        JsonNode value = input.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > maximum)
            throw new IllegalArgumentException("Invalid file pagination.");
        return value.intValue();
    }

    static ObjectNode listPage(Connection connection, long user, JsonNode input) throws SQLException {
        int requestedPage = positiveInteger(input, "page", 1, Integer.MAX_VALUE);
        int pageSize = positiveInteger(input, "pageSize", PAGE_SIZE, PAGE_SIZE);
        long total, used;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*), COALESCE(SUM(size_bytes),0) FROM library_files WHERE user_id=?")) {
            statement.setLong(1, user);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("File totals unavailable.");
                total = result.getLong(1); used = result.getLong(2);
            }
        }
        long pages = Math.max(1, total / pageSize + (total % pageSize == 0 ? 0 : 1));
        int page = (int)Math.min(requestedPage, pages);
        ObjectNode response = ModelGateway.JSON.createObjectNode().put("used", used)
                .put("quota", WorkspaceServlet.QUOTA).put("maxFile", WorkspaceServlet.FILE_MAX)
                .put("page", page).put("pageSize", pageSize).put("total", total).put("hasNext", page < pages);
        ArrayNode files = response.putArray("files");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,name,mime,size_bytes,deleted_at IS NOT NULL FROM library_files "
                        + "WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?")) {
            statement.setLong(1, user); statement.setInt(2, pageSize);
            statement.setLong(3, ((long)page - 1) * pageSize);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) files.addObject().put("id", result.getString(1)).put("name", result.getString(2))
                        .put("mime", result.getString(3)).put("size", result.getLong(4)).put("trashed", result.getBoolean(5));
            }
        }
        return response;
    }
}
