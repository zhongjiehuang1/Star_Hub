-- Run only while all account writers are stopped and the database is backed up.
-- Expired rows can be retained during rollback; the previous application ignores this table.
CREATE TABLE IF NOT EXISTS starhub_account_idle_gate (
 id TINYINT NOT NULL PRIMARY KEY,
 last_clock_millis BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
INSERT IGNORE INTO starhub_account_idle_gate(id,last_clock_millis) VALUES(1,0);

CREATE TABLE IF NOT EXISTS starhub_account_idle_sessions (
 session_nonce CHAR(36) NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,
 user_generation CHAR(36) NOT NULL,
 last_activity_millis BIGINT NOT NULL,
 revoked TINYINT NOT NULL DEFAULT 0,
 INDEX idle_sessions_expiry(last_activity_millis),
 INDEX idle_sessions_user(user_id,user_generation,last_activity_millis),
 CHECK(last_activity_millis>=0),
 CHECK(revoked IN (0,1))
) ENGINE=InnoDB;
