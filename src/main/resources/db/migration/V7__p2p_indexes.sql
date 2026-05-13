-- Supports findByFileIdAndPeerIdAndStatus queries in P2PSessionService
CREATE INDEX idx_sessions_file_peer_status
    ON transfer_sessions (file_id, peer_id, status)
    WHERE peer_id IS NOT NULL;