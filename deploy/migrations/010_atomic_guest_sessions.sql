-- Fence all StarHub writers. Apply to the account/worker-primary schema only.
-- Existing guest memory is temporary; do not delete active SQL state or receipts.
CREATE TABLE IF NOT EXISTS starhub_guest_budget (
 id TINYINT NOT NULL PRIMARY KEY,
 used_units BIGINT NOT NULL DEFAULT 0,
 operation_count BIGINT NOT NULL DEFAULT 0,
 CHECK(used_units>=0),CHECK(operation_count>=0)
) ENGINE=InnoDB;
INSERT IGNORE INTO starhub_guest_budget(id) VALUES(1);
CREATE TABLE IF NOT EXISTS starhub_guest_sessions (
 guest_id CHAR(36) NOT NULL PRIMARY KEY,
 cookie_hash BINARY(32) NOT NULL UNIQUE,
 used_units BIGINT NOT NULL DEFAULT 0,
 operation_count INT NOT NULL DEFAULT 0,
 expires_at_millis BIGINT NOT NULL,
 CHECK(used_units>=0),CHECK(operation_count>=0)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_guest_tabs (
 guest_id CHAR(36) NOT NULL,
 tab_id CHAR(32) NOT NULL,
 last_flush CHAR(32) NULL,
 PRIMARY KEY(guest_id,tab_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_guest_fields (
 guest_id CHAR(36) NOT NULL,
 tab_id CHAR(32) NOT NULL,
 field_name VARCHAR(10) NOT NULL,
 version BIGINT NOT NULL,
 transaction_id CHAR(32) NOT NULL,
 value_cipher LONGBLOB NOT NULL,
 size_units BIGINT NOT NULL,
 PRIMARY KEY(guest_id,tab_id,field_name)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_guest_staging (
 guest_id CHAR(36) NOT NULL,
 tab_id CHAR(32) NOT NULL,
 field_name VARCHAR(10) NOT NULL,
 version BIGINT NOT NULL,
 transaction_id CHAR(32) NOT NULL,
 next_chunk INT NOT NULL,
 value_cipher LONGBLOB NOT NULL,
 size_units BIGINT NOT NULL,
 PRIMARY KEY(guest_id,tab_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_guest_operations (
 guest_id CHAR(36) NOT NULL,
 operation_id CHAR(36) NOT NULL,
 request_hash BINARY(32) NOT NULL,
 result_cipher BLOB NOT NULL,
 PRIMARY KEY(guest_id,operation_id)
) ENGINE=InnoDB;
UPDATE file_storage_atomic_gate SET protocol_version=3 WHERE id=1 AND protocol_version=2;
