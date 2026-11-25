package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.UUID;

/** Account-generation scoped, single-level folders. Caller owns the atomic transaction. */
final class LibraryFolders {
    static final int MAX_FOLDERS=500;
    private LibraryFolders(){}
    static String name(JsonNode body){
        JsonNode value=body.get("name");if(value==null||!value.isTextual())throw invalid();String name=value.asText().trim();
        if(name.isEmpty()||name.length()>120||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.codePoints().anyMatch(Character::isISOControl))throw invalid();return name;
    }
    static StorageFailure invalid(){return new StorageFailure(400,"INVALID_FOLDER","文件夹名称或目标无效。");}
    static String id(JsonNode body,String field,boolean root){
        JsonNode value=body.get(field);if(value==null||!value.isTextual())throw invalid();String id=value.asText();if(root&&id.isEmpty())return "";
        try{if(!UUID.fromString(id).toString().equals(id))throw invalid();}catch(IllegalArgumentException bad){throw invalid();}return id;
    }
    static void requireFolder(Connection c,long user,String generation,String folder)throws SQLException{
        try(PreparedStatement q=c.prepareStatement("SELECT id FROM starhub_library_folders WHERE id=? AND user_id=? AND owner_generation=?")){
            q.setString(1,folder);q.setLong(2,user);q.setString(3,generation);try(ResultSet r=q.executeQuery()){if(!r.next())throw new StorageFailure(404,"FOLDER_NOT_FOUND","文件夹不存在或已更改。");}
        }
    }
    static void requireNameAvailable(Connection c,long user,String generation,String name,String except)throws SQLException{
        try(PreparedStatement q=c.prepareStatement("SELECT id FROM starhub_library_folders WHERE user_id=? AND owner_generation=? AND LOWER(name)=LOWER(?)")){
            q.setLong(1,user);q.setString(2,generation);q.setString(3,name);try(ResultSet r=q.executeQuery()){while(r.next())if(!r.getString(1).equals(except))throw new StorageFailure(409,"FOLDER_EXISTS","已存在同名文件夹。");}
        }
    }
    static ArrayNode list(Connection c,long user,String generation)throws SQLException{
        ArrayNode out=ModelGateway.JSON.createArrayNode();
        try(PreparedStatement q=c.prepareStatement("SELECT id,name,created_at FROM starhub_library_folders WHERE user_id=? AND owner_generation=? ORDER BY name,id LIMIT 501")){
            q.setLong(1,user);q.setString(2,generation);try(ResultSet r=q.executeQuery()){while(r.next()){
                if(out.size()>=MAX_FOLDERS)throw new SQLException("Folder count exceeds its bound");ObjectNode folder=out.addObject().put("id",r.getString(1)).put("name",r.getString(2));Timestamp date=r.getTimestamp(3);if(date!=null)folder.put("createdAt",date.toInstant().toString());
            }}
        }return out;
    }
    /** Remove a placement in the caller's transaction before permanently deleting the file. */
    static void removePlacement(Connection c,long user,String generation,String file)throws SQLException{
        try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_library_placements WHERE file_id=? AND user_id=? AND owner_generation=?")){
            q.setString(1,file);q.setLong(2,user);q.setString(3,generation);q.executeUpdate();
        }
    }
    static ObjectNode apply(Connection c,long user,String generation,String action,JsonNode body)throws SQLException{
        switch(action){
            case "createFolder": {
                String folder=id(body,"operationId",false),name=name(body);requireNameAvailable(c,user,generation,name,folder);ArrayNode folders=list(c,user,generation);if(folders.size()>=MAX_FOLDERS)throw new StorageFailure(413,"FOLDER_LIMIT","文件夹数量已达上限。");
                try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_library_folders(id,user_id,owner_generation,name) VALUES(?,?,?,?)")){q.setString(1,folder);q.setLong(2,user);q.setString(3,generation);q.setString(4,name);q.executeUpdate();}
                return ModelGateway.JSON.createObjectNode().put("id",folder);
            }
            case "renameFolder": {
                String folder=id(body,"folderId",false),name=name(body);requireFolder(c,user,generation,folder);requireNameAvailable(c,user,generation,name,folder);
                try(PreparedStatement q=c.prepareStatement("UPDATE starhub_library_folders SET name=? WHERE id=? AND user_id=? AND owner_generation=?")){q.setString(1,name);q.setString(2,folder);q.setLong(3,user);q.setString(4,generation);q.executeUpdate();}
                return ModelGateway.JSON.createObjectNode().put("ok",true);
            }
            case "deleteFolder": {
                String folder=id(body,"folderId",false);requireFolder(c,user,generation,folder);
                try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM starhub_library_placements WHERE folder_id=? AND user_id=? AND owner_generation=?")){q.setString(1,folder);q.setLong(2,user);q.setString(3,generation);try(ResultSet r=q.executeQuery()){r.next();if(r.getLong(1)>0)throw new StorageFailure(409,"FOLDER_NOT_EMPTY","请先移出文件夹中的文件（包括回收箱中的文件）。");}}
                try(PreparedStatement q=c.prepareStatement("DELETE FROM starhub_library_folders WHERE id=? AND user_id=? AND owner_generation=?")){q.setString(1,folder);q.setLong(2,user);q.setString(3,generation);if(q.executeUpdate()!=1)throw invalid();}
                return ModelGateway.JSON.createObjectNode().put("ok",true);
            }
            case "moveFile": {
                String file=id(body,"id",false),folder=id(body,"folderId",true);if(!folder.isEmpty())requireFolder(c,user,generation,folder);
                try(PreparedStatement q=c.prepareStatement("SELECT id FROM library_files WHERE id=? AND user_id=? AND owner_generation=? AND deleted_at IS NULL")){q.setString(1,file);q.setLong(2,user);q.setString(3,generation);try(ResultSet r=q.executeQuery()){if(!r.next())throw new StorageFailure(404,"FILE_NOT_FOUND","文件不存在或已被移入回收箱。");}}
                removePlacement(c,user,generation,file);
                if(!folder.isEmpty())try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_library_placements(file_id,user_id,owner_generation,folder_id) VALUES(?,?,?,?)")){q.setString(1,file);q.setLong(2,user);q.setString(3,generation);q.setString(4,folder);q.executeUpdate();}
                return ModelGateway.JSON.createObjectNode().put("ok",true);
            }
            default:throw invalid();
        }
    }
}
