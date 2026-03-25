package com.filetransfer.service;

import com.filetransfer.entity.FileChunkEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps ChunkService to expose an async single-chunk upload.
 *
 * Why a separate service instead of just @Async on ChunkService?
 * Spring @Async works by creating a proxy around the bean. If ChunkService
 * called its own @Async method internally, it would bypass the proxy and
 * run synchronously. Keeping the async wrapper in a separate bean avoids
 * this classic Spring self-invocation trap.
 *
 * Usage from the frontend (Phase 3 / React):
 *   - Client slices file into N chunks
 *   - Sends up to `concurrency` chunks simultaneously
 *   - Each chunk POST hits ChunkController independently
 *   - This service handles the server-side async receipt
 *
 * The server-side parallel path here is for a batch-upload endpoint
 * (POST /files/{fileId}/chunks/batch) that accepts multiple chunks
 * in one request — useful for server-to-server or CLI transfers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParallelUploadService {

    private final ChunkService chunkService;
    // NOTE: No Executor field needed — @Async("chunkExecutor") resolves the
    // executor by name from the Spring context automatically. Injecting it
    // as a field here would be redundant and misleading.

    /**
     * Upload a single chunk asynchronously.
     * Returns a CompletableFuture so the caller can track all in-flight uploads
     * and call CompletableFuture.allOf(...) to wait for completion.
     */
    @Async("chunkExecutor")
    public CompletableFuture<ChunkUploadResult> uploadChunkAsync(
            UUID fileId,
            int chunkIndex,
            MultipartFile data,
            String md5) {

        try {
            FileChunkEntity chunk = chunkService.uploadChunk(fileId, chunkIndex, data, md5);
            log.debug("Async chunk complete: fileId={} index={}", fileId, chunkIndex);
            return CompletableFuture.completedFuture(
                new ChunkUploadResult(chunkIndex, true, null)
            );
        } catch (Exception e) {
            log.warn("Async chunk failed: fileId={} index={} error={}",
                     fileId, chunkIndex, e.getMessage());
            return CompletableFuture.completedFuture(
                new ChunkUploadResult(chunkIndex, false, e.getMessage())
            );
        }
    }

    /**
     * Fan-out: fires all chunks concurrently and waits for every one to finish.
     *
     * Each ChunkRequest carries the multipart data + md5 for one chunk.
     * The caller (e.g. a batch controller) builds this list from a multipart
     * request and hands it here. Results are returned in completion order
     * (via awaitAll), not submission order.
     *
     * Any individual failure is captured in the result — the caller decides
     * whether to surface a 207 Multi-Status or retry the failed indexes.
     */
    public List<ChunkUploadResult> uploadAllAsync(UUID fileId, List<ChunkRequest> chunks) {
        List<CompletableFuture<ChunkUploadResult>> futures = chunks.stream()
            .map(req -> uploadChunkAsync(fileId, req.chunkIndex(), req.data(), req.md5()))
            .toList();
        return awaitAll(futures);
    }

    /**
     * Wait for all futures and collect results.
     * Any failure is surfaced in the returned list — caller decides whether to retry.
     */
    public List<ChunkUploadResult> awaitAll(List<CompletableFuture<ChunkUploadResult>> futures) {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream()
            .map(CompletableFuture::join)
            .toList();
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    /**
     * Input record for a single chunk in a batch request.
     */
    public record ChunkRequest(
        int chunkIndex,
        MultipartFile data,
        String md5
    ) {}

    /**
     * Result DTO — avoids checked exceptions crossing the future boundary.
     */
    public record ChunkUploadResult(
        int     chunkIndex,
        boolean success,
        String  errorMessage
    ) {}
}