-- Apply after a verified backup with application writers stopped.
-- Existing accounts, credentials, and generations are preserved.
ALTER TABLE users ADD COLUMN display_name VARCHAR(120) NULL;
