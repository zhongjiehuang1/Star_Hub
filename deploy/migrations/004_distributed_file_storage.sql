-- PRIMARY database only. Stop every Star Hub writer and take a verified backup first.
-- Do not enable storage until BOTH 004 (primary) and 005 (both blob nodes) are applied.
-- Run with a maintenance identity allowed to set restricted session variables.
-- Both MySQL servers must also persist binlog_format=ROW before runtime connections open.
SET SESSION binlog_format='ROW';
SET @starhub_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='generation'),
 'SELECT 1','ALTER TABLE users ADD COLUMN generation CHAR(36) NULL');
PREPARE starhub_stmt FROM @starhub_ddl; EXECUTE starhub_stmt; DEALLOCATE PREPARE starhub_stmt;
-- Adding an evaluated UUID default directly to a nonempty table is unsafe DDL
-- even with ROW binlogging. Backfill as row-logged DML before enforcing the default.
UPDATE users SET generation=UUID() WHERE generation IS NULL;
ALTER TABLE users MODIFY generation CHAR(36) NOT NULL DEFAULT (UUID());
SET @starhub_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='library_files' AND column_name='storage_node_id'),
 'SELECT 1','ALTER TABLE library_files MODIFY content MEDIUMBLOB NULL, ADD COLUMN storage_node_id TINYINT NOT NULL DEFAULT 1, ADD COLUMN owner_generation CHAR(36) NULL');
PREPARE starhub_stmt FROM @starhub_ddl; EXECUTE starhub_stmt; DEALLOCATE PREPARE starhub_stmt;
UPDATE library_files f JOIN users u ON u.id=f.user_id SET f.owner_generation=u.generation WHERE f.owner_generation IS NULL;
ALTER TABLE library_files MODIFY owner_generation CHAR(36) NOT NULL;
CREATE TABLE IF NOT EXISTS file_storage_users (
 user_id BIGINT PRIMARY KEY, generation CHAR(36) NOT NULL, used_bytes BIGINT NOT NULL,
 reserved_bytes BIGINT NOT NULL DEFAULT 0, active_op CHAR(36) NULL,
 CHECK(used_bytes>=0),CHECK(reserved_bytes>=0)
) ENGINE=InnoDB;
INSERT IGNORE INTO file_storage_users(user_id,generation,used_bytes)
 SELECT u.id,u.generation,COALESCE(SUM(f.size_bytes),0) FROM users u LEFT JOIN library_files f ON f.user_id=u.id GROUP BY u.id,u.generation;
CREATE TABLE IF NOT EXISTS file_storage_nodes(node_id TINYINT PRIMARY KEY,reserved_bytes BIGINT NOT NULL DEFAULT 0,CHECK(reserved_bytes>=0)) ENGINE=InnoDB;
INSERT IGNORE INTO file_storage_nodes(node_id) VALUES(1),(2);
CREATE TABLE IF NOT EXISTS file_storage_operations (
 op_id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,user_generation CHAR(36) NOT NULL,request_hash BINARY(32) NOT NULL,
 kind VARCHAR(10) NOT NULL,node_id TINYINT NOT NULL,state VARCHAR(16) NOT NULL,logical_bytes BIGINT NOT NULL,
 physical_bytes BIGINT NOT NULL,primary_physical_bytes BIGINT NOT NULL DEFAULT 0,files_json MEDIUMTEXT NOT NULL,tx_uid VARCHAR(150) NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 INDEX(state,created_at),INDEX(user_id,user_generation)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS file_storage_branches (
 op_id CHAR(36) NOT NULL,node_id TINYINT NOT NULL,format_id INT NOT NULL,gtrid VARBINARY(64) NOT NULL,bqual VARBINARY(64) NOT NULL,
 terminal VARCHAR(10) NULL,PRIMARY KEY(op_id,node_id),UNIQUE KEY branch_xid(node_id,format_id,gtrid,bqual)
) ENGINE=InnoDB;

SET @starhub_ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='file_storage_operations' AND column_name='primary_physical_bytes'),
 'SELECT 1','ALTER TABLE file_storage_operations ADD COLUMN primary_physical_bytes BIGINT NOT NULL DEFAULT 0');
PREPARE starhub_stmt FROM @starhub_ddl; EXECUTE starhub_stmt; DEALLOCATE PREPARE starhub_stmt;
CREATE TABLE IF NOT EXISTS file_storage_coordinator (
 id TINYINT PRIMARY KEY,store_identity CHAR(36) NOT NULL,
 CHECK(id=1)
) ENGINE=InnoDB;
INSERT IGNORE INTO file_storage_coordinator(id,store_identity) VALUES(1,UUID());
