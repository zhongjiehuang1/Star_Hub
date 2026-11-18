-- Apply through the controlled single-database migration workflow. Existing
-- accounts remain opted out until each account enables automatic memory.
-- RESTRICT is required by the atomic-storage foreign-key audit. A future
-- account deletion workflow must explicitly clean these encrypted rows in its
-- own reviewed, capacity-admitted, idempotent transaction before deleting users.
CREATE TABLE IF NOT EXISTS starhub_memory_settings (
 user_id BIGINT NOT NULL,
 owner_generation CHAR(36) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 revision BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY (user_id, owner_generation),
 CONSTRAINT starhub_memory_settings_owner_fk FOREIGN KEY (user_id)
  REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS starhub_account_memories (
 id CHAR(36) NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,
 owner_generation CHAR(36) NOT NULL,
 kind VARCHAR(32) NOT NULL,
 fingerprint BINARY(32) NOT NULL,
 nonce BINARY(12) NOT NULL,
 ciphertext VARBINARY(2048) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY memory_dedup (user_id, owner_generation, fingerprint),
 INDEX memory_owner (user_id, owner_generation, updated_at, created_at, id),
 CONSTRAINT starhub_account_memories_owner_fk FOREIGN KEY (user_id)
  REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
