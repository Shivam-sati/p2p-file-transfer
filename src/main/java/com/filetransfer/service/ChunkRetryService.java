package com.filetransfer.service;

import com.filetransfer.entity.FileChunkEntity;
import com.filetransfer.repository.ChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Adds retry tracking on top of ChunkService.
 *
 * When a chunk upload fails transiently (disk full, I/O error),
 * we want to:
 *  1. Retry up to MAX_CHUNK_RETRIES times with backoff
 *  2. Record the retry count in the DB for observability
 *  3. Mark the chunk as FAILED if all retries are exhausted
 *
 * This is separate from ChunkService to keep the retry concern isolated.
 *
 * NOTE on @Transactional placement: incrementRetryCount() is intentionally
 * extracted into a public method on this same bean so that Spring's proxy
 * intercepts the @Transactional annotation correctly. Placing @Transactional
 * on a protected/private method called internally bypasses the proxy and
 * silently runs without a transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkRetryService {

    private static final int MAX_CHUNK_RETRIES = 3;

    private final ChunkService    chunkService;
    private final ChunkRepository chunkRepository;

    /**
     * Upload a chunk with automatic retry.
     *
     * On each transient failure, increments retry_count in the DB so operators
     * can query "which chunks needed the most retries" in analytics.
     * If all retries are exhausted, the chunk is marked FAILED in the DB
     * before the exception propagates to the caller.
     */
    public FileChunkEntity uploadWithRetry(UUID fileId, int chunkIndex,
                                           MultipartFile data, String md5) {
        try {
            return RetryHandler.retry(
                MAX_CHUNK_RETRIES,
                "chunk-upload[" + fileId + "/" + chunkIndex + "]",
                () -> {
                    try {
                        return chunkService.uploadChunk(fileId, chunkIndex, data, md5);
                    } catch (Exception e) {
                        // Increment on every failed attempt so the count reflects
                        // actual retries, not just the final outcome.
                        incrementRetryCount(fileId, chunkIndex);
                        throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                    }
                }
            );
        } catch (Exception e) {
            // All retries exhausted — mark the chunk as permanently FAILED
            // so the expiry/orphan-cleanup jobs (and the UI) can reflect this.
            markChunkFailed(fileId, chunkIndex);
            throw e;
        }
    }

    // ── package-visible so Spring proxy intercepts @Transactional ────────────

    /**
     * Increment retry_count for observability.
     * Public so Spring's transactional proxy wraps it correctly —
     * internal (protected/private) calls bypass the proxy.
     */
    @Transactional
    public void incrementRetryCount(UUID fileId, int chunkIndex) {
        chunkRepository.findByFileIdAndChunkIndex(fileId, chunkIndex)
            .ifPresent(chunk -> {
                chunk.setRetryCount(chunk.getRetryCount() + 1);
                chunkRepository.save(chunk);
                log.debug("Retry count incremented: fileId={} chunkIndex={} retries={}",
                          fileId, chunkIndex, chunk.getRetryCount());
            });
    }

    /**
     * Mark a chunk as FAILED after all retries are exhausted.
     * Public for the same proxy reason as above.
     */
    @Transactional
    public void markChunkFailed(UUID fileId, int chunkIndex) {
        chunkRepository.findByFileIdAndChunkIndex(fileId, chunkIndex)
            .ifPresent(chunk -> {
                chunk.setStatus(FileChunkEntity.Status.FAILED);  // inner enum — not a top-level ChunkStatus class
                chunkRepository.save(chunk);
                log.warn("Chunk permanently FAILED after {} retries: fileId={} chunkIndex={}",
                         MAX_CHUNK_RETRIES, fileId, chunkIndex);
            });
    }
}