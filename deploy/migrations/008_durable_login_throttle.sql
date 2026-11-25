-- Same maintenance/drain requirements as 007. No credential plaintext is stored.
CREATE TABLE IF NOT EXISTS starhub_login_limits (
 identity_hash BINARY(32) NOT NULL PRIMARY KEY,
 window_start_millis BIGINT NOT NULL,
 failures INT NOT NULL DEFAULT 0,
 CHECK(failures>=0)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_login_attempts (
 operation_id CHAR(36) NOT NULL PRIMARY KEY,
 identity_hash BINARY(32) NOT NULL,
 request_hash BINARY(32) NOT NULL,
 email_hash BINARY(32) NOT NULL,
 outcome_status SMALLINT NOT NULL,
 attempted_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX(identity_hash,attempted_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_login_status_limits (
 identity_hash BINARY(32) NOT NULL PRIMARY KEY,
 window_start_millis BIGINT NOT NULL,
 requests INT NOT NULL DEFAULT 0,
 CHECK(requests>=0)
) ENGINE=InnoDB;
