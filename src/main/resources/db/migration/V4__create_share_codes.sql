CREATE TABLE share_codes (
    id                 UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    file_id            UUID        NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    code               VARCHAR(16) NOT NULL UNIQUE,
    max_downloads      INT,
    download_count     INT         NOT NULL DEFAULT 0,
    password_protected BOOLEAN     NOT NULL DEFAULT FALSE,
    password_hash      VARCHAR(128),
    expires_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_share_codes_code    ON share_codes (code);
CREATE INDEX idx_share_codes_file_id ON share_codes (file_id);
CREATE INDEX idx_share_codes_expires ON share_codes (expires_at)
    WHERE expires_at IS NOT NULL;