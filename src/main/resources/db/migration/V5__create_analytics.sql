-- V5__create_transfer_analytics.sql
-- Fine-grained event log for the analytics dashboard (Phase 5).
-- Append-only — never update rows; only insert.

CREATE TYPE analytics_event_type AS ENUM (
    'UPLOAD_START',
    'CHUNK_UPLOADED',
    'UPLOAD_COMPLETE',
    'UPLOAD_FAILED',
    'DOWNLOAD_START',
    'DOWNLOAD_PROGRESS',
    'DOWNLOAD_COMPLETE',
    'DOWNLOAD_FAILED',
    'P2P_INITIATED',
    'P2P_CONNECTED',
    'P2P_FALLBACK',         -- P2P failed; fell back to server
    'P2P_COMPLETE',
    'FILE_EXPIRED'
);

CREATE TYPE transfer_mode AS ENUM ('SERVER', 'P2P');

CREATE TABLE transfer_analytics (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id          UUID            REFERENCES transfer_sessions(id) ON DELETE SET NULL,
    event_type          analytics_event_type NOT NULL,
    bytes_at_event      BIGINT          NOT NULL DEFAULT 0,
    speed_bps           DOUBLE PRECISION,
    transfer_mode       transfer_mode,
    error_code          VARCHAR(64),
    metadata            JSONB,                      -- flexible bag for extra context
    recorded_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Analytics queries are almost always time-range scans — BRIN is highly efficient here
CREATE INDEX idx_analytics_recorded_at  ON transfer_analytics USING BRIN (recorded_at);
CREATE INDEX idx_analytics_session_id   ON transfer_analytics (session_id);
CREATE INDEX idx_analytics_event_type   ON transfer_analytics (event_type);