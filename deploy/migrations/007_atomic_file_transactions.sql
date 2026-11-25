-- Maintenance only: all old coordinators stopped and reliably fenced. Back up
-- and inspect XA RECOVER on both physical members; resolve every old decision.
-- Runtime additionally refuses pending operations, active gates or held quota.
-- Apply to the canonical account schema; both logical schemas must be InnoDB,
-- on this SAME local MySQL, and fully replicated to both physical members.
CREATE TABLE IF NOT EXISTS file_storage_atomic_gate (
 id TINYINT NOT NULL PRIMARY KEY,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
 protocol_version INT NOT NULL,
 CHECK(id=1)
) ENGINE=InnoDB;
INSERT IGNORE INTO file_storage_atomic_gate(id,revision,protocol_version) VALUES(1,0,1);
CREATE TABLE IF NOT EXISTS starhub_request_results (
 operation_id CHAR(36) NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,
 user_generation CHAR(36) NOT NULL,
 endpoint VARCHAR(120) NOT NULL,
 request_hash BINARY(32) NOT NULL,
 response_status SMALLINT NOT NULL,
 response_type VARCHAR(120) NOT NULL,
 response_body MEDIUMBLOB NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX(user_id,user_generation)
) ENGINE=InnoDB;
