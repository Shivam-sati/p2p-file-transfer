-- V2__create_chunks.sql
-- One row per chunk. Allows resume: client can ask which chunks already exist.

CREATE TYPE chunk_status AS ENUM (
    'PENDING',      -- not yet uploaded
    'UPLOADED',     -- stored on disk, awaiting merge
    'MERGED',       -- incorporated into the final file
    'FAILED'        -- upload or checksum error
);

CREATE TABLE file_chunks (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    file_id         UUID            NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    chunk_index     INT             NOT NULL,   -- 0-based index
    chunk_size      BIGINT          NOT NULL,   -- actual bytes received
    byte_offset     BIGINT          NOT NULL,   -- position in the final file (chunk_index * chunk_size)
    checksum_md5    VARCHAR(32),                -- per-chunk integrity (optional but recommended)
    storage_path    VARCHAR(1024)   NOT NULL,   -- path to the chunk file on disk
    status          chunk_status    NOT NULL DEFAULT 'PENDING',
    retry_count     INT             NOT NULL DEFAULT 0,
    uploaded_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- Each chunk index must be unique per file
    CONSTRAINT uq_file_chunk UNIQUE (file_id, chunk_index)
);

CREATE INDEX idx_chunks_file_id ON file_chunks (file_id);
CREATE INDEX idx_chunks_status  ON file_chunks (file_id, status);