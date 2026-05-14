CREATE TABLE transfer_analytics (
    id             UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id     UUID        REFERENCES transfer_sessions(id) ON DELETE SET NULL,
    event_type     VARCHAR(50) NOT NULL,
    bytes_at_event BIGINT      NOT NULL DEFAULT 0,
    speed_bps      DOUBLE PRECISION,
    transfer_mode  VARCHAR(10),
    error_code     VARCHAR(64),
    metadata       JSONB,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- BRIN index is ideal for append-only time-series data
CREATE INDEX idx_analytics_recorded_at ON transfer_analytics USING BRIN (recorded_at);
CREATE INDEX idx_analytics_session_id  ON transfer_analytics (session_id);
CREATE INDEX idx_analytics_event_type  ON transfer_analytics (event_type);