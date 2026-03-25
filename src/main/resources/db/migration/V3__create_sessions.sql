-- V3__create_transfer_sessions.sql
-- Tracks every upload or download attempt (server or P2P).

CREATE TYPE session_type AS ENUM ('SERVER', 'P2P');
CREATE TYPE session_direction AS ENUM ('UPLOAD', 'DOWNLOAD');
CREATE TYPE session_status AS ENUM (
    'ACTIVE', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'PEER_FALLBACK'
);

CREATE TABLE transfer_sessions (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    file_id             UUID            NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    session_type        session_type    NOT NULL DEFAULT 'SERVER',
    direction           session_direction NOT NULL,
    status              session_status  NOT NULL DEFAULT 'ACTIVE',

    -- WebRTC fields (Phase 3 — nullable until then)
    peer_id             VARCHAR(128),
    signaling_data      TEXT,           -- JSON SDP/ICE stored as text

    -- Client info
    client_ip           VARCHAR(45),
    user_agent          VARCHAR(512),

    -- Metrics
    bytes_transferred   BIGINT          NOT NULL DEFAULT 0,
    avg_speed_bps       DOUBLE PRECISION,       -- bytes per second

    started_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sessions_file_id ON transfer_sessions (file_id);
CREATE INDEX idx_sessions_status  ON transfer_sessions (status);