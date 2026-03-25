package com.filetransfer.service;

import com.filetransfer.entity.FileChunkEntity;
import com.filetransfer.entity.FileEntity;
import com.filetransfer.exception.ChunkMissingException;
import com.filetransfer.exception.InvalidFileStateException;
import com.filetransfer.repository.ChunkRepository;
import com.filetransfer.repository.FileRepository;
import com.filetransfer.util.ChecksumUtil;
import com.filetransfer.util.StorageUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * Assembles uploaded chunks into a single file without loading it into memory.
 *
 * Strategy:
 *   - Open the output file with FileChannel (writable, create)
 *   - For each chunk (in order), open its file with a read-only FileChannel
 *   - Use FileChannel.transferTo() which delegates to sendfile(2) on Linux —
 *     the data moves from disk to disk entirely in kernel space.
 *   - After assembly, stream the whole file once to compute SHA-256 and
 *     compare it against the client's declared hash.
 *
 * Thread safety:
 *   - The @Transactional status transition to MERGING acts as a distributed
 *     "lock" so two concurrent merge requests can't race. The second caller
 *     sees status=MERGING and throws InvalidFileStateException.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MergeService {

    private static final int TRANSFER_BUFFER = 4 * 1024 * 1024; // 4 MB kernel buffer hint

    private final FileRepository  fileRepository;
    private final ChunkRepository chunkRepository;
    private final Path            storagePath;

    /**
     * Entry point called by the controller.
     * Validates preconditions synchronously, then delegates to the async assembler.
     */
    @Transactional
    public void triggerMerge(UUID fileId) {
        FileEntity file = fileRepository.findById(fileId)
            .orElseThrow(() -> new com.filetransfer.exception.FileNotFoundException(fileId));

        // Guard: only UPLOADING files can be merged
        if (file.getStatus() != FileEntity.Status.UPLOADING) {
            throw new InvalidFileStateException(
                "Merge already requested or file not in UPLOADING state. Current: " + file.getStatus());
        }

        // Guard: all chunks must be present
        long uploadedCount = chunkRepository.countByFileIdAndStatus(fileId, FileChunkEntity.Status.UPLOADED);
        if (uploadedCount != file.getTotalChunks()) {
            throw new ChunkMissingException(
                "Expected " + file.getTotalChunks() + " chunks but only " + uploadedCount + " uploaded");
        }

        // Transition to MERGING — this is our optimistic "lock"
        file.setStatus(FileEntity.Status.MERGING);
        fileRepository.save(file);

        log.info("Merge triggered for fileId={} chunks={}", fileId, file.getTotalChunks());

        // Hand off to the async executor (mergeExecutor bean from AsyncConfig)
        assembleAsync(fileId);
    }

    /**
     * The actual assembly runs asynchronously so the HTTP response returns immediately.
     * The client can poll GET /files/{fileId} to watch status change from MERGING → READY.
     */
    @Async("mergeExecutor")
    public void assembleAsync(UUID fileId) {
        FileEntity file = fileRepository.findById(fileId).orElseThrow();

        try {
            Path outputPath = buildOutputPath(file);
            assembleChunks(fileId, outputPath);
            ChecksumUtil.verifySha256(outputPath, file.getChecksumSha256());
            markReady(file, outputPath);
            log.info("Merge complete: fileId={} path={}", fileId, outputPath);
        } catch (Exception e) {
            log.error("Merge failed for fileId={}: {}", fileId, e.getMessage(), e);
            markFailed(file, e.getMessage());
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * Stream-assemble all chunks in order using FileChannel.transferTo().
     *
     * transferTo() calls the OS-level sendfile(2) syscall on Linux, which copies
     * data between file descriptors entirely in kernel space. No data ever passes
     * through the JVM heap — critical for large files.
     */
    private void assembleChunks(UUID fileId, Path outputPath) throws IOException {
        List<FileChunkEntity> chunks = chunkRepository.findByFileIdOrderByChunkIndexAsc(fileId);

        // Validate that chunk indexes form a complete, gap-free sequence [0..N-1]
        validateChunkSequence(chunks);

        try (FileChannel out = FileChannel.open(outputPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            for (FileChunkEntity chunk : chunks) {
                Path chunkPath = Paths.get(chunk.getStoragePath());

                try (FileChannel in = FileChannel.open(chunkPath, StandardOpenOption.READ)) {
                    long remaining = in.size();
                    long position  = 0;

                    // transferTo has a platform limit (~2GB per call) — loop to be safe
                    while (remaining > 0) {
                        long transferred = in.transferTo(position, Math.min(remaining, TRANSFER_BUFFER), out);
                        if (transferred <= 0) {
                            throw new IOException("transferTo returned " + transferred +
                                " for chunk " + chunk.getChunkIndex());
                        }
                        position  += transferred;
                        remaining -= transferred;
                    }
                }

                log.debug("Assembled chunk {}/{}", chunk.getChunkIndex() + 1,
                    chunkRepository.countByFileIdAndStatus(fileId, FileChunkEntity.Status.UPLOADED));
            }
        }
    }

    private void validateChunkSequence(List<FileChunkEntity> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            FileChunkEntity chunk = chunks.get(i);
            if (chunk.getChunkIndex() != i) {
                throw new ChunkMissingException(
                    "Missing chunk at index " + i + " (found " + chunk.getChunkIndex() + ")");
            }
            if (chunk.getStatus() != FileChunkEntity.Status.UPLOADED) {
                throw new ChunkMissingException(
                    "Chunk " + i + " is in state " + chunk.getStatus() + ", expected UPLOADED");
            }
        }
    }

    private Path buildOutputPath(FileEntity file) throws IOException {
        return StorageUtil.mergedFilePath(storagePath, file.getId(), file.getFileName());
    }

    @Transactional
    protected void markReady(FileEntity file, Path outputPath) {
        file.setStatus(FileEntity.Status.READY);
        file.setStoragePath(outputPath.toString());
        fileRepository.save(file);
        // Mark all chunks as merged so queries don't confuse them with active uploads
        chunkRepository.markAllMergedByFileId(file.getId());
    }

    @Transactional
    protected void markFailed(FileEntity file, String reason) {
        file.setStatus(FileEntity.Status.FAILED);
        fileRepository.save(file);
    }
}