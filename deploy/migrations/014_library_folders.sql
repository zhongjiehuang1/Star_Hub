-- Run with both application writers stopped after a verified backup.
-- Folder metadata is account-generation scoped; original files remain intact.
-- After creating these tables, grant the existing workspace DB principal only
-- SELECT, INSERT, UPDATE and DELETE on starhub_library_folders, and SELECT,
-- INSERT and DELETE on starhub_library_placements. Verify using that principal;
-- table creation by an administrator does not automatically grant app access.
-- RESTRICT is required by the atomic storage startup audit. File deletion
-- explicitly removes its folder placement in the same transaction. Existing
-- installations with the original CASCADE rules need the reviewed 016 upgrade.
CREATE TABLE IF NOT EXISTS starhub_library_folders (
 id CHAR(36) NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,
 owner_generation CHAR(36) NOT NULL,
 name VARCHAR(120) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY library_folder_owner_name (user_id,owner_generation,name),
 CONSTRAINT starhub_folder_owner_restrict_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS starhub_library_placements (
 file_id CHAR(36) NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,
 owner_generation CHAR(36) NOT NULL,
 folder_id CHAR(36) NOT NULL,
 INDEX library_placement_owner_folder (user_id,owner_generation,folder_id),
 CONSTRAINT starhub_placement_file_restrict_fk FOREIGN KEY (file_id) REFERENCES library_files(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
