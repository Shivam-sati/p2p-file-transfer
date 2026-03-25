-- V1__create_files.sql
-- Core file record. One row per logical file (before and after merging).

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TYPE file_status AS ENUM (
    'UPLOADING',    -- chunks are still being received
    'MERGING',      -- merge job is in progress
    'READY',        -- fully assembled and ready to download
    'FAILED',       -- merge or checksum failed
    'DELETED'       -- soft-deleted; storage may have been reclaimed
);

CREATE TABLE files (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    file_name           VARCHAR(512)    NOT NULL,  -- sanitised storage name
    original_name       VARCHAR(512)    NOT NULL,  -- original client filename
    file_size           BIGINT          NOT NULL,  -- total bytes (declared by client)
    mime_type           VARCHAR(128),
    checksum_sha256     VARCHAR(64)     NOT NULL,  -- declared by client; verified after merge
    storage_path        VARCHAR(1024),             -- absolute path once merged
    status              file_status     NOT NULL DEFAULT 'UPLOADING',
    total_chunks        INT             NOT NULL,
    uploaded_chunks     INT             NOT NULL DEFAULT 0,
    uploader_session_id UUID,
    uploader_ip         VARCHAR(45),               -- IPv4 or IPv6
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Fast lookups by status (e.g. cron that deletes DELETED rows)
CREATE INDEX idx_files_status   ON files (status);
CREATE INDEX idx_files_expires  ON files (expires_at) WHERE expires_at IS NOT NULL;

-- Auto-update updated_at on every row change
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_files_updated_at
BEFORE UPDATE ON files
FOR EACH ROW EXECUTE FUNCTION set_updated_at();