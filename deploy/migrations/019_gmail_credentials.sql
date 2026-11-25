-- Gmail credentials are separate from API keys, never returned to the browser.
-- Apply only through the controlled single-database migration workflow after
-- verifying backup, encryption-key custody, capacity and rollback plans.
-- The ciphertext is an AES-GCM sealed OAuth refresh token bound to the owning
-- account generation; no email body, OAuth client secret or plaintext token is
-- stored here. A REVOKING row is disabled locally while revocation is confirmed.
CREATE TABLE IF NOT EXISTS starhub_gmail_credentials (
 user_id BIGINT NOT NULL,
 owner_generation CHAR(36) NOT NULL,
 status VARCHAR(16) NOT NULL,
 nonce BINARY(12) NOT NULL,
 ciphertext VARBINARY(8192) NOT NULL,
 updated_millis BIGINT NOT NULL,
 PRIMARY KEY (user_id, owner_generation),
 CONSTRAINT starhub_gmail_owner_fk FOREIGN KEY (user_id)
   REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

-- Tracks the one-shot OAuth exchange independently of any browser session.
-- A row is deliberately retained through uncertain outcomes until the owner
-- checks/revokes the Google grant. It contains no authorization code or token.
CREATE TABLE IF NOT EXISTS starhub_gmail_authorizations (
 user_id BIGINT NOT NULL,
 owner_generation CHAR(36) NOT NULL,
 operation_id CHAR(36) NOT NULL,
 status VARCHAR(24) NOT NULL,
 updated_millis BIGINT NOT NULL,
 PRIMARY KEY (user_id, owner_generation),
 UNIQUE KEY starhub_gmail_operation (operation_id),
 CONSTRAINT starhub_gmail_authorization_owner_fk FOREIGN KEY (user_id)
   REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
