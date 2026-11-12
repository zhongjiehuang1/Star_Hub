-- Apply to the selected Star Hub database before enabling large file libraries.
-- Existing libraries keep their contents; repeated execution is safe.
SET @starhub_page_index_exists = (
 SELECT COUNT(*) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='library_files'
 AND index_name='library_files_user_created'
);
SET @starhub_page_index_ddl = IF(@starhub_page_index_exists > 0,
 'SELECT 1',
 'ALTER TABLE library_files ADD INDEX library_files_user_created(user_id,created_at,id)');
PREPARE starhub_page_index_statement FROM @starhub_page_index_ddl;
EXECUTE starhub_page_index_statement;
DEALLOCATE PREPARE starhub_page_index_statement;
