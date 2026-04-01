package com.filetransfer.service;

import com.filetransfer.entity.FileChunkEntity;
import com.filetransfer.entity.FileEntity;
import com.filetransfer.exception.ChunkMissingException;
import com.filetransfer.exception.InvalidFileStateException;
import com.filetransfer.repository.ChunkRepository;
import com.filetransfer.repository.FileRepository;
import com.filetransfer.service.progress.ProgressBroadcaster;
import com.filetransfer.util.ChecksumUtil;
import com.filetransfer.util.StorageUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * Merges uploaded file chunks into a single file using zero-copy transfer.
 *
 * Design goals:
 * - Avoid loading large files into JVM heap
 * - Prevent duplicate merge requests
 * - Support async processing
 * - Provide progress/failure notifications to frontend
 * - Produce detailed logs for debugging production issues
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MergeService {

    /**
     * transferTo() may internally chunk large copies depending on OS/JVM.
     * 4 MB is a safe balance between syscall overhead and memory pressure.
     */
    private static final long TRANSFER_BUFFER = 4L * 1024 * 1024;

    private final FileRepository fileRepository;
    private final ChunkRepository chunkRepository;
    private final Path storagePath;
    private final ProgressBroadcaster progressBroadcaster;

    /**
     * Called by controller when the client requests merge.
     *
     * Validates:
     * - File exists
     * - File is still in UPLOADING state
     * - All expected chunks are present
     *
     * Then transitions the file to MERGING and starts async assembly.
     */
    @Transactional
    public void triggerMerge(UUID fileId) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new com.filetransfer.exception.FileNotFoundException(fileId));

        // Prevent duplicate or invalid merge attempts
        if (file.getStatus() != FileEntity.Status.UPLOADING) {
            throw new InvalidFileStateException(
                    "Merge can only be triggered when file is in UPLOADING state. Current state: "
                            + file.getStatus());
        }

        // Ensure every chunk has arrived
        long uploadedChunkCount = chunkRepository.countByFileIdAndStatus(fileId, FileChunkEntity.Status.UPLOADED);

        if (uploadedChunkCount != file.getTotalChunks()) {
            throw new ChunkMissingException(
                    "Cannot merge file. Expected " + file.getTotalChunks()
                            + " uploaded chunks but found " + uploadedChunkCount);
        }

        // Optimistic lock: prevent concurrent merge requests
        file.setStatus(FileEntity.Status.MERGING);
        fileRepository.save(file);

        log.info(
                "Merge triggered for fileId={} totalChunks={} fileName={}",
                fileId,
                file.getTotalChunks(),
                file.getFileName());

        assembleAsync(fileId);
    }

    /**
     * Performs the merge asynchronously so the HTTP request can return immediately.
     *
     * Flow:
     * 1. Merge chunks into final file
     * 2. Verify SHA-256 checksum
     * 3. Mark file READY
     * 4. Notify frontend
     */
    @Async("mergeExecutor")
    public void assembleAsync(UUID fileId) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalStateException("File disappeared during merge: " + fileId));

        try {
            Path outputPath = buildOutputPath(file);

            log.info("Starting chunk assembly for fileId={} output={}", fileId, outputPath);

            assembleChunks(fileId, outputPath);

            log.info("Chunk assembly complete for fileId={}, verifying checksum...", fileId);

            ChecksumUtil.verifySha256(outputPath, file.getChecksumSha256());

            log.info("Checksum verification passed for fileId={}", fileId);

            markReady(file, outputPath);

            progressBroadcaster.uploadComplete(fileId, file.getFileSize());

            log.info(
                    "Merge completed successfully for fileId={} finalPath={}",
                    fileId,
                    outputPath);

        } catch (Exception e) {
            log.error(
                    "Merge failed for fileId={} reason={}",
                    fileId,
                    e.getMessage(),
                    e);

            markFailed(file, e.getMessage());

            progressBroadcaster.uploadFailed(fileId, e.getMessage());
        }
    }

    /**
     * Merges all chunks into the final output file using FileChannel.transferTo().
     *
     * transferTo() uses zero-copy file transfer where possible, avoiding JVM heap
     * usage.
     */
    private void assembleChunks(UUID fileId, Path outputPath) throws IOException {
        List<FileChunkEntity> chunks = chunkRepository.findByFileIdOrderByChunkIndexAsc(fileId);

        validateChunkSequence(chunks);

        try (FileChannel outputChannel = FileChannel.open(
                outputPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            int totalChunks = chunks.size();

            for (int index = 0; index < chunks.size(); index++) {
                FileChunkEntity chunk = chunks.get(index);

                Path chunkPath = Paths.get(chunk.getStoragePath());

                log.debug(
                        "Merging chunk {}/{} for fileId={} chunkPath={}",
                        index + 1,
                        totalChunks,
                        fileId,
                        chunkPath);

                try (FileChannel inputChannel = FileChannel.open(chunkPath, StandardOpenOption.READ)) {

                    long remaining = inputChannel.size();
                    long position = 0;

                    while (remaining > 0) {
                        long transferred = inputChannel.transferTo(
                                position,
                                Math.min(remaining, TRANSFER_BUFFER),
                                outputChannel);

                        if (transferred <= 0) {
                            throw new IOException(
                                    "transferTo failed for chunk index="
                                            + chunk.getChunkIndex()
                                            + ", transferred="
                                            + transferred
                                            + ", remaining="
                                            + remaining);
                        }

                        position += transferred;
                        remaining -= transferred;
                    }
                }

                log.debug(
                        "Chunk {}/{} merged successfully for fileId={}",
                        index + 1,
                        totalChunks,
                        fileId);
            }
        }
    }

    /**
     * Ensures chunk indexes are complete and sequential:
     * [0, 1, 2, ..., N-1]
     */
    private void validateChunkSequence(List<FileChunkEntity> chunks) {
        for (int expectedIndex = 0; expectedIndex < chunks.size(); expectedIndex++) {
            FileChunkEntity chunk = chunks.get(expectedIndex);

            if (chunk.getChunkIndex() != expectedIndex) {
                throw new ChunkMissingException(
                        "Missing chunk at index " + expectedIndex
                                + ". Found chunk with index " + chunk.getChunkIndex());
            }

            if (chunk.getStatus() != FileChunkEntity.Status.UPLOADED) {
                throw new ChunkMissingException(
                        "Chunk " + expectedIndex
                                + " is in invalid state: " + chunk.getStatus()
                                + ". Expected UPLOADED.");
            }
        }
    }

    private Path buildOutputPath(FileEntity file) throws IOException {
        return StorageUtil.mergedFilePath(
                storagePath,
                file.getId(),
                file.getFileName());
    }

    /**
     * Marks file as fully merged and available for download.
     */
    @Transactional
    protected void markReady(FileEntity file, Path outputPath) {
        file.setStatus(FileEntity.Status.READY);
        file.setStoragePath(outputPath.toString());

        fileRepository.save(file);

        // Mark chunks as MERGED so they no longer appear as active uploads
        chunkRepository.markAllMergedByFileId(file.getId());

        log.info("File marked READY for fileId={}", file.getId());
    }

    /**
     * Marks merge as failed.
     */
    @Transactional
    protected void markFailed(FileEntity file, String reason) {
        file.setStatus(FileEntity.Status.FAILED);

        fileRepository.save(file);

        log.warn(
                "File marked FAILED for fileId={} reason={}",
                file.getId(),
                reason);
    }
}
