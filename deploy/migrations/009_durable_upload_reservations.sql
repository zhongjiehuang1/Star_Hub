-- All writers fenced; legacy XA/pending reservations drained. Apply after 007/008.
-- Pending uploads are durable promises, never presumed-aborted by timeout.
CREATE TABLE IF NOT EXISTS starhub_upload_reservations (
 op_id CHAR(36) NOT NULL PRIMARY KEY,
 owner_node_id TINYINT NOT NULL,
 owner_mysql_uuid CHAR(36) NOT NULL,
 owner_epoch VARCHAR(20) NOT NULL,
 owner_permit CHAR(64) NOT NULL,
 reserved_view VARCHAR(200) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_capacity_totals (
 node_id TINYINT NOT NULL PRIMARY KEY,
 reserved_bytes BIGINT NOT NULL DEFAULT 0,
 CHECK(reserved_bytes>=0)
) ENGINE=InnoDB;
INSERT IGNORE INTO starhub_capacity_totals(node_id,reserved_bytes) VALUES(1,0),(2,0);
CREATE TABLE IF NOT EXISTS starhub_reserved_capacity (
 op_id CHAR(36) NOT NULL,
 node_id TINYINT NOT NULL,
 reserved_bytes BIGINT NOT NULL,
 PRIMARY KEY(op_id,node_id),
 CHECK(reserved_bytes>=0)
) ENGINE=InnoDB;
UPDATE file_storage_atomic_gate SET protocol_version=2 WHERE id=1 AND protocol_version=1;
