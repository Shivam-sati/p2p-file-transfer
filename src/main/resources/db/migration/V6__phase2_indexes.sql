CREATE INDEX idx_files_status_updated  ON files (status, updated_at);
CREATE INDEX idx_chunks_status_created ON file_chunks (status, created_at);