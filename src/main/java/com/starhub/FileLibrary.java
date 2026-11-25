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

    static ObjectNode listPage(Connection connection,long user,JsonNode input)throws SQLException{return listPage(connection,user,null,input);}
    static ObjectNode listPage(Connection connection, long user, String generation, JsonNode input) throws SQLException {
        int requestedPage = positiveInteger(input, "page", 1, Integer.MAX_VALUE);
        int pageSize = positiveInteger(input, "pageSize", PAGE_SIZE, PAGE_SIZE);
        JsonNode searchValue=input.get("search"),viewValue=input.get("view");
        if(searchValue!=null&&(!searchValue.isTextual()||searchValue.asText().length()>120))throw new IllegalArgumentException("Invalid file search.");
        if(viewValue!=null&&(!viewValue.isTextual()||!java.util.Set.of("all","active","trash").contains(viewValue.asText())))throw new IllegalArgumentException("Invalid file view.");
        String search=searchValue==null?"":searchValue.asText().trim(),view=viewValue==null?"all":viewValue.asText();
        String folder="";boolean folderFilter=false;
        if(generation!=null){
            JsonNode folderValue=input.get("folderId");if(folderValue!=null){folder=LibraryFolders.id(input,"folderId",true);if(!folder.isEmpty())LibraryFolders.requireFolder(connection,user,generation,folder);}
            folderFilter=view.equals("active")&&(!folder.isEmpty()||search.isEmpty());
        }
        String condition=" WHERE user_id=?"+(generation!=null?" AND owner_generation=?":"")+(view.equals("active")?" AND deleted_at IS NULL":view.equals("trash")?" AND deleted_at IS NOT NULL":"")+(search.isEmpty()?"":" AND LOCATE(LOWER(?),LOWER(name))>0")
          +(folderFilter?(folder.isEmpty()?" AND NOT EXISTS (SELECT 1 FROM starhub_library_placements p WHERE p.file_id=library_files.id)":" AND EXISTS (SELECT 1 FROM starhub_library_placements p WHERE p.file_id=library_files.id AND p.folder_id=?)"):"");
        long total, used;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*), COALESCE(SUM(size_bytes),0) FROM library_files WHERE user_id=?"+(generation!=null?" AND owner_generation=?":""))) {
            statement.setLong(1, user);if(generation!=null)statement.setString(2,generation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("File totals unavailable.");
                total = result.getLong(1); used = result.getLong(2);
            }
        }
        if(!search.isEmpty()||!view.equals("all")||folderFilter){
            try(PreparedStatement statement=connection.prepareStatement("SELECT COUNT(*) FROM library_files"+condition)){
                statement.setLong(1,user);int countIndex=2;if(generation!=null)statement.setString(countIndex++,generation);if(!search.isEmpty())statement.setString(countIndex++,search);if(folderFilter&&!folder.isEmpty())statement.setString(countIndex,folder);
                try(ResultSet result=statement.executeQuery()){if(!result.next())throw new SQLException("File count unavailable.");total=result.getLong(1);}
            }
        }
        long pages = Math.max(1, total / pageSize + (total % pageSize == 0 ? 0 : 1));
        int page = (int)Math.min(requestedPage, pages);
        ObjectNode response = ModelGateway.JSON.createObjectNode().put("used", used)
                .put("quota", WorkspaceServlet.QUOTA).put("maxFile", WorkspaceServlet.FILE_MAX)
                .put("page", page).put("pageSize", pageSize).put("total", total).put("hasNext", page < pages);
        if(generation!=null)response.set("folders",LibraryFolders.list(connection,user,generation));
        ArrayNode files = response.putArray("files");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,name,mime,size_bytes,deleted_at IS NOT NULL,created_at"+(generation!=null?",(SELECT p.folder_id FROM starhub_library_placements p WHERE p.file_id=library_files.id)":"")+" FROM library_files"
                        + condition + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?")) {
            statement.setLong(1,user);int index=2;if(generation!=null)statement.setString(index++,generation);if(!search.isEmpty())statement.setString(index++,search);if(folderFilter&&!folder.isEmpty())statement.setString(index++,folder);
            statement.setInt(index++,pageSize);statement.setLong(index,((long)page-1)*pageSize);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ObjectNode file=files.addObject().put("id",result.getString(1)).put("name",result.getString(2))
                        .put("mime",result.getString(3)).put("size",result.getLong(4)).put("trashed",result.getBoolean(5));
                    java.sql.Timestamp created=result.getTimestamp(6);if(created!=null)file.put("createdAt",created.toInstant().toString());
                    if(generation!=null){String location=result.getString(7);if(location!=null)file.put("folderId",location);}
                }
            }
        }
        return response;
    }
}
