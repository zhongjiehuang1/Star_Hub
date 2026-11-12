-- Reviewed maintenance window with formal/candidate writers stopped.
-- Execute on canonical member with binary logging and BEFORE_AND_AFTER; GR copies it.
CREATE TABLE IF NOT EXISTS starhub_provider_operations (
 operation_id CHAR(36) NOT NULL PRIMARY KEY,
 owner_hash BINARY(32) NOT NULL,
 request_hash BINARY(32) NOT NULL,
 kind VARCHAR(120) NOT NULL,
 state VARCHAR(32) NOT NULL,
 account_user_id BIGINT NULL,
 account_generation CHAR(36) NULL,
 image_budget_bytes BIGINT NOT NULL DEFAULT 0,
 logical_reserved BIGINT NOT NULL DEFAULT 0,
 dispatch_hash BINARY(32) NULL,
 reserved_view VARCHAR(200) NOT NULL,
 response_status SMALLINT NULL,
 result_cipher LONGBLOB NULL,
 created_millis BIGINT NOT NULL,
 updated_millis BIGINT NOT NULL,
 CHECK(image_budget_bytes>=0 AND image_budget_bytes<=50331648),
 CHECK(logical_reserved>=0 AND logical_reserved<=image_budget_bytes),
 CHECK((account_user_id IS NULL AND account_generation IS NULL AND logical_reserved=0) OR (account_user_id IS NOT NULL AND account_generation IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_provider_capacity (
 operation_id CHAR(36) NOT NULL,
 node_id TINYINT NOT NULL,
 reserved_bytes BIGINT NOT NULL,
 PRIMARY KEY(operation_id,node_id),
 CHECK(reserved_bytes>=0)
) ENGINE=InnoDB;
UPDATE file_storage_atomic_gate SET protocol_version=4 WHERE id=1 AND protocol_version=3;
