-- Apply only to the canonical logical Star_Hub schema after a verified backup,
-- while ALL writers are stopped and every prior operation is terminal. Do not
-- enable storage.physicalNodeIds until this migration and coordinated member
-- configuration have been verified on every replica.
CREATE TABLE IF NOT EXISTS file_storage_replica_reservations (
 op_id CHAR(36) NOT NULL, node_id INT UNSIGNED NOT NULL, reserved_bytes BIGINT NOT NULL,
 PRIMARY KEY(op_id,node_id), CHECK(reserved_bytes>0),
 CONSTRAINT fk_file_storage_replica_operation FOREIGN KEY(op_id) REFERENCES file_storage_operations(op_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS file_storage_physical_nodes (
 node_id INT UNSIGNED PRIMARY KEY, reserved_bytes BIGINT NOT NULL DEFAULT 0,
 CHECK(node_id>0),CHECK(reserved_bytes>=0)
) ENGINE=InnoDB;
