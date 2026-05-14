-- V2__create_chunks.sql
-- Uses VARCHAR for status instead of custom ENUM type.

CREATE TABLE file_chunks (
    id           UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    file_id      UUID          NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    chunk_index  INT           NOT NULL,
    chunk_size   BIGINT        NOT NULL,
    byte_offset  BIGINT        NOT NULL,
    checksum_md5 VARCHAR(32),
    storage_path VARCHAR(1024) NOT NULL,
    status       VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    retry_count  INT           NOT NULL DEFAULT 0,
    uploaded_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_file_chunk UNIQUE (file_id, chunk_index)
);

CREATE INDEX idx_chunks_file_id ON file_chunks (file_id);
CREATE INDEX idx_chunks_status  ON file_chunks (file_id, status);