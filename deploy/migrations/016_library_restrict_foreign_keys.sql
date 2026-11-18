-- Upgrade only the original 014 schema with its two auto-generated CASCADE
-- foreign keys. Inspect the exact constraint names and rules first; do not run
-- this against an already upgraded database. Stop application writers and
-- take a verified backup before applying DDL. The replacement preserves rows.
ALTER TABLE starhub_library_folders
 DROP FOREIGN KEY starhub_library_folders_ibfk_1,
 ADD CONSTRAINT starhub_folder_owner_restrict_fk FOREIGN KEY (user_id)
 REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE starhub_library_placements
 DROP FOREIGN KEY starhub_library_placements_ibfk_1,
 ADD CONSTRAINT starhub_placement_file_restrict_fk FOREIGN KEY (file_id)
 REFERENCES library_files(id) ON DELETE RESTRICT ON UPDATE RESTRICT;
