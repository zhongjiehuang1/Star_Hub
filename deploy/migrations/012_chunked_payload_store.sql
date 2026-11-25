-- Immutable payload bytes are written one <=2-MiB chunk per confirmed TX.
-- Caller reserves physical/logical budget BEFORE chunks and releases it only
-- after CANCELLING, every bounded deletion, and confirmed CANCELLED. Keep header
-- tombstones. No timeout cleanup, cascade, gate UPDATE or existing-row rewrite.
CREATE TABLE IF NOT EXISTS starhub_payload_manifests (
 payload_id CHAR(36) NOT NULL PRIMARY KEY,
 operation_id CHAR(36) NOT NULL,
 kind VARCHAR(32) NOT NULL,
 owner_hash BINARY(32) NOT NULL,
 request_hash BINARY(32) NOT NULL,
 byte_count BIGINT NOT NULL,
 chunk_count INT NOT NULL,
 sha256 BINARY(32) NOT NULL,
 state VARCHAR(16) NOT NULL,
 CHECK(byte_count>=0 AND byte_count<=2000000000),
 CHECK(chunk_count>=0 AND chunk_count<=954),
 CHECK(state IN ('STAGING','READY','PUBLISHED','CANCELLING','CANCELLED')),
 INDEX starhub_payload_operation(operation_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_payload_chunks (
 payload_id CHAR(36) NOT NULL,
 chunk_index INT NOT NULL,
 byte_count INT NOT NULL,
 sha256 BINARY(32) NOT NULL,
 content MEDIUMBLOB NOT NULL,
 PRIMARY KEY(payload_id,chunk_index),
 CHECK(chunk_index>=0 AND chunk_index<954),
 CHECK(byte_count>0 AND byte_count<=2097152),
 CONSTRAINT starhub_payload_chunk_header FOREIGN KEY(payload_id)
  REFERENCES starhub_payload_manifests(payload_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
