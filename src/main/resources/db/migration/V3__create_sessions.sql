CREATE TABLE transfer_sessions (
    id                UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    file_id           UUID         NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    session_type      VARCHAR(20)  NOT NULL DEFAULT 'SERVER',
    direction         VARCHAR(20)  NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    peer_id           VARCHAR(128),
    signaling_data    TEXT,
    client_ip         VARCHAR(45),
    user_agent        VARCHAR(512),
    bytes_transferred BIGINT       NOT NULL DEFAULT 0,
    avg_speed_bps     DOUBLE PRECISION,
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sessions_file_id ON transfer_sessions (file_id);
CREATE INDEX idx_sessions_status  ON transfer_sessions (status);