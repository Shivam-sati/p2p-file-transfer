CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE files (
    id                  UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    file_name           VARCHAR(512) NOT NULL,
    original_name       VARCHAR(512) NOT NULL,
    file_size           BIGINT       NOT NULL,
    mime_type           VARCHAR(128),
    checksum_sha256     VARCHAR(64)  NOT NULL,
    storage_path        VARCHAR(1024),
    status              VARCHAR(20)  NOT NULL DEFAULT 'UPLOADING',
    total_chunks        INT          NOT NULL,
    uploaded_chunks     INT          NOT NULL DEFAULT 0,
    uploader_ip         VARCHAR(45),
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_files_status  ON files (status);
CREATE INDEX idx_files_expires ON files (expires_at) WHERE expires_at IS NOT NULL;

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_files_updated_at
    BEFORE UPDATE ON files
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();