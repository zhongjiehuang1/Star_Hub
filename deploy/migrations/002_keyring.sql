CREATE TABLE IF NOT EXISTS api_keys (
 id CHAR(36) NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,
 provider VARCHAR(40) NOT NULL,
 label VARCHAR(120) NOT NULL,
 key_digest BINARY(32) NOT NULL,
 nonce VARBINARY(12) NOT NULL,
 ciphertext BLOB NOT NULL,
 selected BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY unique_user_key (user_id,key_digest),
 INDEX selected_provider (user_id,provider,selected),
 FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
