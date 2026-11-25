package com.starhub;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountFeatureSchemaTest {
    private static void verify(AtomicTransactionsTest.Fixture f)throws Exception {try(Connection c=f.connection()){AccountFeatureSchema.verify(c,f.p);}}
    private static void sql(AtomicTransactionsTest.Fixture f,String sql)throws Exception {try(Connection c=f.connection();Statement q=c.createStatement()){q.execute(sql);}}
    private static void migration(AtomicTransactionsTest.Fixture f,String name)throws Exception {
        try(Connection c=f.connection();Statement q=c.createStatement()){
            String ddl=Files.readString(Path.of("deploy/migrations/"+name)).replaceAll("(?m)^--.*$","").replace("ENGINE=InnoDB","");
            for(String statement:ddl.split(";"))if(!statement.isBlank())q.execute(statement);
        }
    }
    @Test void memoryIsRequiredAndIdleSchemaIsOnlyRequiredWhenEnabled()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            verify(f);f.p.setProperty("account.idle.enabled","true");assertThrows(SQLException.class,()->verify(f));
            migration(f,"017_account_idle_sessions.sql");verify(f);
            sql(f,"DELETE FROM starhub_account_idle_gate WHERE id=1");assertThrows(SQLException.class,()->verify(f));
            f.p.setProperty("account.idle.enabled","false");verify(f);
            sql(f,"DROP TABLE starhub_account_memories");assertThrows(SQLException.class,()->verify(f));
        }
    }
    @Test void oldMemoryTableWithoutUpdatedAtIsRejected()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            sql(f,"DROP INDEX memory_owner");sql(f,"ALTER TABLE starhub_account_memories DROP COLUMN updated_at");assertThrows(SQLException.class,()->verify(f));
        }
    }
    @Test void missingMemoryIndexesOrOwnerForeignKeyAreRejected()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            sql(f,"DROP INDEX memory_owner");assertThrows(SQLException.class,()->verify(f));
            sql(f,"CREATE INDEX memory_owner ON starhub_account_memories(user_id,owner_generation,updated_at,created_at,id)");verify(f);
            sql(f,"ALTER TABLE starhub_account_memories DROP CONSTRAINT starhub_account_memories_owner_fk");assertThrows(SQLException.class,()->verify(f));
        }
    }
    @Test void missingIdleExpiryIndexIsRejectedOnlyWhenIdleIsEnabled()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            migration(f,"017_account_idle_sessions.sql");f.p.setProperty("account.idle.enabled","true");verify(f);
            sql(f,"DROP INDEX idle_sessions_expiry");assertThrows(SQLException.class,()->verify(f));f.p.setProperty("account.idle.enabled","false");verify(f);
        }
    }
    @Test void runtimeHealthRejectsMissingMemorySchemaWithoutWriting()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            String identity=UUID.randomUUID().toString();sql(f,"CREATE TABLE file_storage_coordinator(id INT PRIMARY KEY,store_identity CHAR(36))");
            sql(f,"INSERT INTO file_storage_coordinator VALUES(1,'"+identity+"')");long before=f.count("SELECT revision FROM file_storage_atomic_gate");
            f.db.runtimeProbe(identity);sql(f,"DROP TABLE starhub_memory_settings");
            assertThrows(SQLException.class,()->f.db.runtimeProbe(identity));assertEquals(before,f.count("SELECT revision FROM file_storage_atomic_gate"));
        }
    }
    @Test void startupRejectsMissingMemorySchema()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            verify(f);sql(f,"DROP TABLE starhub_memory_settings");var missing=assertThrows(SQLException.class,()->f.db.verifyDrainedSchema());assertTrue(missing.getMessage().contains("starhub_memory_settings"));
        }
    }
    @Test void repeatedMigrationsAndIdleStartupHealthRespectTheFeatureFlag()throws Exception {
        try(var f=new AtomicTransactionsTest.Fixture()){
            String identity=UUID.randomUUID().toString();sql(f,"CREATE TABLE file_storage_coordinator(id INT PRIMARY KEY,store_identity CHAR(36))");sql(f,"INSERT INTO file_storage_coordinator VALUES(1,'"+identity+"')");
            migration(f,"018_account_memory.sql");f.db.runtimeProbe(identity);
            f.p.setProperty("account.idle.enabled","true");var startup=assertThrows(SQLException.class,()->f.db.verifyDrainedSchema());assertTrue(startup.getMessage().contains("starhub_account_idle_gate"));
            var health=assertThrows(SQLException.class,()->f.db.runtimeProbe(identity));assertTrue(health.getMessage().contains("starhub_account_idle_gate"));
            migration(f,"017_account_idle_sessions.sql");migration(f,"017_account_idle_sessions.sql");assertEquals(1,f.count("SELECT COUNT(*) FROM starhub_account_idle_gate WHERE id=1"));f.db.runtimeProbe(identity);
        }
    }
}
