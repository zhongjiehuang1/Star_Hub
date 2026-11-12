-- Apply after 011/012, canonical member only; final gate advances last.
CREATE TABLE IF NOT EXISTS starhub_file_payloads(
 file_id CHAR(36) NOT NULL PRIMARY KEY,
 operation_id CHAR(36) NOT NULL,
 deleting_op CHAR(36) NULL,
 plan_json TEXT NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_guest_values(
 guest_id CHAR(36) NOT NULL,tab_id CHAR(32) NOT NULL,field_name VARCHAR(10) NOT NULL,
 version BIGINT NOT NULL,transaction_id CHAR(32) NOT NULL,refs_json MEDIUMTEXT NOT NULL,
 size_units BIGINT NOT NULL,
 PRIMARY KEY(guest_id,tab_id,field_name)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_guest_streams(
 guest_id CHAR(36) NOT NULL,tab_id CHAR(32) NOT NULL,field_name VARCHAR(10) NOT NULL,
 version BIGINT NOT NULL,transaction_id CHAR(32) NOT NULL,next_chunk INT NOT NULL,
 refs_json MEDIUMTEXT NOT NULL,size_units BIGINT NOT NULL,
 PRIMARY KEY(guest_id,tab_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_guest_cleanup(
 cleanup_id CHAR(36) NOT NULL PRIMARY KEY,guest_id CHAR(36) NOT NULL,
 refs_json MEDIUMTEXT NOT NULL,size_units BIGINT NOT NULL,state VARCHAR(16) NOT NULL,
 legacy_kind VARCHAR(16) NULL,legacy_tab CHAR(32) NULL,legacy_field VARCHAR(10) NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_guest_payload_refs(
 payload_id CHAR(36) NOT NULL PRIMARY KEY,guest_id CHAR(36) NOT NULL,
 live_refs BIGINT NOT NULL DEFAULT 0,cleanup_refs BIGINT NOT NULL DEFAULT 0,
 staging_refs BIGINT NOT NULL DEFAULT 0,
 CHECK(live_refs>=0),CHECK(cleanup_refs>=0),CHECK(staging_refs>=0 AND staging_refs<=live_refs)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_provider_payloads(
 operation_id CHAR(36) NOT NULL PRIMARY KEY,dispatch_hash BINARY(32) NOT NULL,
 response_status SMALLINT NOT NULL,terminal_state VARCHAR(32) NOT NULL,
 plan_json TEXT NOT NULL,cipher_binding VARCHAR(200) NOT NULL
) ENGINE=InnoDB;
UPDATE file_storage_atomic_gate SET protocol_version=5 WHERE id=1 AND protocol_version=4;
