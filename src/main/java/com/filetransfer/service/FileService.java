package com.filetransfer.service;

import com.filetransfer.config.AppConfig.AppProperties;
import com.filetransfer.dto.request.FileInitRequest;
import com.filetransfer.entity.FileEntity;
import com.filetransfer.exception.FileNotFoundException;
import com.filetransfer.exception.InvalidFileStateException;
import com.filetransfer.repository.ChunkRepository;
import com.filetransfer.repository.FileRepository;
import com.filetransfer.util.StorageUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileService {

    private final FileRepository    fileRepository;
    private final ChunkRepository   chunkRepository;
    private final AppProperties     props;
    private final Path              storagePath;

    /**
     * Initialise a new upload session.
     *
     * The client must tell us the total file size, number of chunks, and the
     * SHA-256 of the complete file upfront. We store this declaration and
     * verify it after merge — "trust but verify" model.
     */
    @Transactional
    public FileEntity initUpload(FileInitRequest req, String uploaderIp) {
        validateFileSize(req.getFileSize());

        String sanitisedName = StorageUtil.sanitise(req.getOriginalName(), UUID.randomUUID());

        FileEntity file = new FileEntity();
        file.setOriginalName(req.getOriginalName());
        file.setFileName(sanitisedName);
        file.setFileSize(req.getFileSize());
        file.setMimeType(req.getMimeType());
        file.setChecksumSha256(req.getChecksumSha256().toLowerCase());
        file.setTotalChunks(req.getTotalChunks());
        file.setUploaderIp(uploaderIp);
        file.setStatus(FileEntity.Status.UPLOADING);

        FileEntity saved = fileRepository.save(file);
        log.info("Upload initialised: fileId={} name='{}' size={} chunks={}",
            saved.getId(), req.getOriginalName(), req.getFileSize(), req.getTotalChunks());

        return saved;
    }

    /**
     * Fetch a file record, throwing if deleted or not found.
     */
    public FileEntity getFile(UUID fileId) {
        return fileRepository.findByIdAndStatusNot(fileId, FileEntity.Status.DELETED)
            .orElseThrow(() -> new FileNotFoundException(fileId));
    }

    /**
     * Soft-delete a file — marks status DELETED, then schedules physical cleanup.
     * Physical deletion is handled by a separate scheduled task so this call is fast.
     */
    @Transactional
    public void deleteFile(UUID fileId) {
        FileEntity file = getFile(fileId);
        if (file.getStatus() == FileEntity.Status.MERGING) {
            throw new InvalidFileStateException("Cannot delete file while merge is in progress");
        }
        file.setStatus(FileEntity.Status.DELETED);
        fileRepository.save(file);
        log.info("File soft-deleted: {}", fileId);

        // Kick off physical cleanup asynchronously (Phase 2 adds a cron fallback too)
        cleanupStorage(file);
    }

    // ── private ──────────────────────────────────────────────────────────────

    private void validateFileSize(long fileSize) {
        if (fileSize <= 0) throw new IllegalArgumentException("fileSize must be positive");
        if (fileSize > props.maxFileSizeBytes()) {
            throw new IllegalArgumentException(
                "File size " + fileSize + " exceeds limit of " + props.maxFileSizeBytes());
        }
    }

    private void cleanupStorage(FileEntity file) {
        // Delete chunk directory (always exists once upload has started)
        StorageUtil.deleteRecursively(storagePath.resolve("chunks").resolve(file.getId().toString()));

        // Delete merged file directory (only exists if file reached READY)
        if (file.getStoragePath() != null) {
            StorageUtil.deleteRecursively(storagePath.resolve("files").resolve(file.getId().toString()));
        }
    }
}