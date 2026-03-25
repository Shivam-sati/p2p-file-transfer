package com.filetransfer.service;

import com.filetransfer.entity.FileChunkEntity;
import com.filetransfer.entity.FileEntity;
import com.filetransfer.repository.ChunkRepository;
import com.filetransfer.repository.FileRepository;
import com.filetransfer.util.StorageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Scheduled tasks for storage hygiene.
 *
 * Three jobs run independently:
 *
 * 1. expireFiles()         — every hour
 *    Finds files whose expires_at < NOW and marks them DELETED.
 *    Deletes their storage directories immediately.
 *
 * 2. purgeDeletedFiles()   — every 6 hours
 *    Hard-deletes DB rows for files already marked DELETED.
 *    Separating soft-delete from hard-delete gives a grace window
 *    for operators to recover accidentally deleted files.
 *
 * 3. cleanOrphanedChunks() — every 24 hours at 03:00
 *    Removes chunk files on disk that belong to abandoned/incomplete uploads,
 *    caused by crashes mid-upload or clients that never completed.
 *
 * NOTE on @Transactional: we use Spring's annotation
 * (org.springframework.transaction.annotation.Transactional), not Jakarta's,
 * so Spring Boot's transaction proxy intercepts it correctly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileExpiryService {

    private final FileRepository  fileRepository;
    private final ChunkRepository chunkRepository;
    private final Path            storagePath;

    @Value("${app.upload.chunk-ttl-hours:24}")
    private int chunkTtlHours;

    // ── Job 1: expire files ───────────────────────────────────────────────────

    /**
     * Runs every hour. Marks expired files as DELETED and cleans their storage.
     * Uses fixedDelay so the next run only starts after the previous one finishes —
     * prevents overlap if the job takes longer than 1 hour on a large dataset.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void expireFiles() {
        List<FileEntity> expired = fileRepository.findExpired(Instant.now());
        if (expired.isEmpty()) return;

        log.info("Expiry job: found {} expired file(s)", expired.size());

        for (FileEntity file : expired) {
            try {
                file.setStatus(FileEntity.Status.DELETED);
                fileRepository.save(file);
                deleteStorageForFile(file);
                log.info("Expired file: id={} name='{}'", file.getId(), file.getOriginalName());
            } catch (Exception e) {
                // Log and continue — one bad file shouldn't block the rest
                log.error("Failed to expire file {}: {}", file.getId(), e.getMessage());
            }
        }
    }

    // ── Job 2: hard-purge soft-deleted rows ──────────────────────────────────

    /**
     * Runs every 6 hours. Permanently removes DB rows for DELETED files.
     * Files must have been in DELETED state for at least 1 hour before purging
     * (grace window — checked via updated_at index added in V6 migration).
     */
    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT30M")
    @Transactional
    public void purgeDeletedFiles() {
        // Only purge files that were soft-deleted more than 1 hour ago
        Instant cutoff = Instant.now().minusSeconds(3600);

        List<FileEntity> toDelete = fileRepository
            .findByStatusAndUpdatedAtBefore(FileEntity.Status.DELETED, cutoff);

        if (toDelete.isEmpty()) return;

        log.info("Purge job: hard-deleting {} soft-deleted file record(s)", toDelete.size());
        fileRepository.deleteAll(toDelete);  // CASCADE deletes chunks, share codes, sessions
    }

    // ── Job 3: orphaned chunk cleanup ────────────────────────────────────────

    /**
     * Runs once per day at 03:00.
     * Finds chunk records older than chunkTtlHours that are still PENDING or FAILED,
     * deletes their files from disk, then removes the DB record.
     *
     * IMPORTANT: disk delete and DB delete are paired per-chunk inside the loop.
     * Deleting the DB row only after a successful disk delete prevents the scenario
     * where a swallowed disk exception causes a dangling DB record pointing to a
     * file that no longer exists (or vice-versa).
     *
     * These orphans arise when:
     *  - A client uploaded chunks but never completed the upload
     *  - An upload was abandoned mid-way through
     */
    @Scheduled(cron = "0 0 3 * * *")   // 03:00 every day
    @Transactional
    public void cleanOrphanedChunks() {
        Instant cutoff = Instant.now().minusSeconds(chunkTtlHours * 3600L);

        List<FileChunkEntity> orphans = chunkRepository.findOrphanedChunks(cutoff);
        if (orphans.isEmpty()) return;

        log.info("Orphan cleanup: removing {} stale chunk(s)", orphans.size());

        int removed = 0;
        for (FileChunkEntity chunk : orphans) {
            try {
                // Delete from disk first — if this fails, we keep the DB record
                // so the next cleanup run can try again.
                StorageUtil.deleteRecursively(
                    java.nio.file.Paths.get(chunk.getStoragePath())
                );
                // Only remove the DB record once the file is gone from disk
                chunkRepository.delete(chunk);
                removed++;
            } catch (Exception e) {
                log.warn("Could not delete orphaned chunk {} (will retry next run): {}",
                         chunk.getStoragePath(), e.getMessage());
            }
        }

        log.info("Orphan cleanup complete: {}/{} chunk(s) removed", removed, orphans.size());
    }

    // ── private ──────────────────────────────────────────────────────────────

    private void deleteStorageForFile(FileEntity file) {
        // Delete chunk staging directory
        StorageUtil.deleteRecursively(
            storagePath.resolve("chunks").resolve(file.getId().toString())
        );
        // Delete merged file directory (only exists if file reached READY state)
        if (file.getStoragePath() != null) {
            StorageUtil.deleteRecursively(
                storagePath.resolve("files").resolve(file.getId().toString())
            );
        }
    }
}