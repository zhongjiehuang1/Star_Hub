-- Additive migration in starhub_accounts. Apply before enabling the worker.
CREATE TABLE IF NOT EXISTS starhub_intelligence_state (
 user_id BIGINT NOT NULL,
 owner_generation CHAR(36) NOT NULL,
 control JSON NOT NULL,
 snapshot MEDIUMBLOB,
 PRIMARY KEY (user_id, owner_generation),
 CONSTRAINT starhub_intelligence_owner_fk FOREIGN KEY (user_id)
  REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS starhub_intelligence_events (
 user_id BIGINT NOT NULL,
 owner_generation CHAR(36) NOT NULL,
 event_id CHAR(36) NOT NULL,
 kind VARCHAR(16) NOT NULL,
 happened_ms BIGINT NOT NULL,
 payload MEDIUMBLOB NOT NULL,
 PRIMARY KEY (user_id, owner_generation, event_id),
 INDEX intelligence_history (user_id, owner_generation, kind, happened_ms, event_id),
 CONSTRAINT starhub_intelligence_event_owner_fk FOREIGN KEY (user_id, owner_generation)
  REFERENCES starhub_intelligence_state(user_id, owner_generation) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS starhub_intelligence_mail (
 user_id BIGINT NOT NULL,
 owner_generation CHAR(36) NOT NULL,
 message_id CHAR(36) NOT NULL,
 action_id CHAR(36) NOT NULL,
 state VARCHAR(20) NOT NULL,
 created_ms BIGINT NOT NULL,
 due_ms BIGINT NOT NULL,
 first_attempt_ms BIGINT NOT NULL DEFAULT 0,
 attempts INT NOT NULL DEFAULT 0,
 provider_id CHAR(36),
 credential_hash CHAR(64),
 payload MEDIUMBLOB NOT NULL,
 PRIMARY KEY (user_id, owner_generation, message_id),
 UNIQUE KEY intelligence_action_mail (user_id, owner_generation, action_id),
 INDEX intelligence_mail_due (user_id, owner_generation, state, due_ms),
 CONSTRAINT starhub_intelligence_mail_owner_fk FOREIGN KEY (user_id, owner_generation)
  REFERENCES starhub_intelligence_state(user_id, owner_generation) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
