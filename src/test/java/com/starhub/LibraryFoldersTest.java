package com.starhub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LibraryFoldersTest {
    static final String GEN="11111111-1111-4111-8111-111111111111", OTHER="22222222-2222-4222-8222-222222222222";
    static ObjectNode body(String action){return ModelGateway.JSON.createObjectNode().put("action",action);}
    static Connection database() throws Exception {
        Connection c=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        try(Statement q=c.createStatement()){
            q.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            q.execute("CREATE TABLE library_files(id CHAR(36) PRIMARY KEY,user_id BIGINT,owner_generation CHAR(36),name VARCHAR(120),mime VARCHAR(100),size_bytes BIGINT,deleted_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            q.execute("CREATE TABLE starhub_library_folders(id CHAR(36) PRIMARY KEY,user_id BIGINT,owner_generation CHAR(36),name VARCHAR(120),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,owner_generation,name))");
            q.execute("CREATE TABLE starhub_library_placements(file_id CHAR(36) PRIMARY KEY REFERENCES library_files(id) ON DELETE RESTRICT,user_id BIGINT,owner_generation CHAR(36),folder_id CHAR(36))");
            q.execute("INSERT INTO users VALUES(1),(2)");
        }
        return c;
    }
    static void file(Connection c,String id,long user,String generation,String name) throws Exception{
        try(PreparedStatement q=c.prepareStatement("INSERT INTO library_files(id,user_id,owner_generation,name,mime,size_bytes) VALUES(?,?,?,?,'text/plain',10)")){q.setString(1,id);q.setLong(2,user);q.setString(3,generation);q.setString(4,name);q.executeUpdate();}
    }
    @Test void foldersAndMovesAreScopedSearchableAndRollbackTogether() throws Exception{
        String folder=UUID.randomUUID().toString(),one=UUID.randomUUID().toString(),two=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        try(Connection c=database()){
            file(c,one,1,GEN,"Alpha.txt");file(c,two,1,GEN,"Beta.txt");file(c,other,2,OTHER,"Secret.txt");
            LibraryFolders.apply(c,1,GEN,"createFolder",body("createFolder").put("operationId",folder).put("name","Projects"));
            LibraryFolders.apply(c,1,GEN,"moveFile",body("moveFile").put("id",one).put("folderId",folder));
            ObjectNode root=body("listFiles").put("view","active").put("folderId","");
            var result=FileLibrary.listPage(c,1,GEN,root);assertEquals(1,result.path("total").asInt());assertEquals("Beta.txt",result.path("files").get(0).path("name").asText());assertEquals(1,result.path("folders").size());
            var inside=FileLibrary.listPage(c,1,GEN,root.deepCopy().put("folderId",folder));assertEquals("Alpha.txt",inside.path("files").get(0).path("name").asText());
            assertEquals("Alpha.txt",FileLibrary.listPage(c,1,GEN,root.deepCopy().put("search","alpha")).path("files").get(0).path("name").asText());
            assertThrows(StorageFailure.class,()->LibraryFolders.apply(c,2,OTHER,"moveFile",body("moveFile").put("id",other).put("folderId",folder)));
            assertThrows(StorageFailure.class,()->LibraryFolders.apply(c,1,GEN,"moveFile",body("moveFile").put("id",other).put("folderId",folder)));
            assertThrows(StorageFailure.class,()->LibraryFolders.apply(c,1,GEN,"deleteFolder",body("deleteFolder").put("folderId",folder)));
            c.setAutoCommit(false);LibraryFolders.apply(c,1,GEN,"moveFile",body("moveFile").put("id",one).put("folderId",""));c.rollback();c.setAutoCommit(true);
            assertEquals(1,FileLibrary.listPage(c,1,GEN,root.deepCopy().put("folderId",folder)).path("total").asInt());
            LibraryFolders.apply(c,1,GEN,"renameFolder",body("renameFolder").put("folderId",folder).put("name","New name"));
            assertEquals("New name",LibraryFolders.list(c,1,GEN).get(0).path("name").asText());
            c.setAutoCommit(false);
            LibraryFolders.removePlacement(c,1,GEN,one);
            try(PreparedStatement q=c.prepareStatement("DELETE FROM library_files WHERE id=?")){q.setString(1,one);q.executeUpdate();}
            c.rollback();
            assertEquals(1,FileLibrary.listPage(c,1,GEN,root.deepCopy().put("folderId",folder)).path("total").asInt());
            LibraryFolders.removePlacement(c,1,GEN,one);
            try(PreparedStatement q=c.prepareStatement("DELETE FROM library_files WHERE id=?")){q.setString(1,one);q.executeUpdate();}
            c.commit();c.setAutoCommit(true);
            LibraryFolders.apply(c,1,GEN,"deleteFolder",body("deleteFolder").put("folderId",folder));assertEquals(0,LibraryFolders.list(c,1,GEN).size());
        }
    }
    @Test void invalidNamesDuplicateNamesAndWrongGenerationsAreRejected() throws Exception{
        try(Connection c=database()){
            String folder=UUID.randomUUID().toString();LibraryFolders.apply(c,1,GEN,"createFolder",body("createFolder").put("operationId",folder).put("name","Projects"));
            for(String name:new String[]{" ","bad/name","bad\\name","bad\nname"})assertThrows(StorageFailure.class,()->LibraryFolders.apply(c,1,GEN,"createFolder",body("createFolder").put("operationId",UUID.randomUUID().toString()).put("name",name)));
            assertThrows(StorageFailure.class,()->LibraryFolders.apply(c,1,GEN,"createFolder",body("createFolder").put("operationId",UUID.randomUUID().toString()).put("name","projects")));
            assertThrows(StorageFailure.class,()->LibraryFolders.requireFolder(c,1,OTHER,folder));
        }
    }
    @Test void permanentlyDeletingAFileInAFolderRemovesItsPlacement() throws Exception{
        try(var f=new AtomicTransactionsTest.Fixture()){
            String file=f.files.upload(f.ingress(),1,UUID.randomUUID().toString(),java.util.List.of(new FileStorage.Upload("file.txt","text/plain",new byte[]{1,2,3}))).path("id").asText();
            String folder=UUID.randomUUID().toString();
            try(Connection c=f.connection()){
                LibraryFolders.apply(c,1,f.generation,"createFolder",body("createFolder").put("operationId",folder).put("name","Documents"));
                LibraryFolders.apply(c,1,f.generation,"moveFile",body("moveFile").put("id",file).put("folderId",folder));
            }
            f.files.trash(1,UUID.randomUUID().toString(),file,false);
            assertEquals("COMMITTED",f.files.delete(1,UUID.randomUUID().toString(),file).path("state").asText());
            assertEquals(0,f.count("SELECT COUNT(*) FROM starhub_library_placements"));
            assertEquals(0,f.count("SELECT COUNT(*) FROM library_files"));
            try(Connection c=f.connection()){
                LibraryFolders.apply(c,1,f.generation,"deleteFolder",body("deleteFolder").put("folderId",folder));
            }
        }
    }
}
