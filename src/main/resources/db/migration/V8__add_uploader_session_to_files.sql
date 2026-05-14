ALTER TABLE files
ADD COLUMN uploader_session_id UUID;

ALTER TABLE files
ADD CONSTRAINT fk_files_uploader_session
FOREIGN KEY (uploader_session_id)
REFERENCES transfer_sessions(id)
ON DELETE SET NULL;