-- ============================================================
-- V6: Phase 2 indexes
-- ============================================================
-- Indexes added for the expiry cron and orphan cleanup queries.
-- ============================================================

-- Supports: findByStatusAndUpdatedAtBefore (hard-purge job)
CREATE INDEX idx_files_status_updated
    ON files (status, updated_at);

-- Supports: findOrphanedChunks (orphaned chunk cleanup)
CREATE INDEX idx_chunks_status_created
    ON file_chunks (status, created_at);