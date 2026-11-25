-- Apply to each configured blob database, including PRIMARY if it is blob node 1.
CREATE TABLE IF NOT EXISTS file_blobs (
 id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,owner_generation CHAR(36) NOT NULL,
 content MEDIUMBLOB NOT NULL,sha256 BINARY(32) NOT NULL,size_bytes INT NOT NULL,
 INDEX(user_id,owner_generation)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS file_storage_receipts (
 op_id CHAR(36) PRIMARY KEY,committed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
