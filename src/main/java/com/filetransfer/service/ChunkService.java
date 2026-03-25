package com.filetransfer.service;

import com.filetransfer.config.AppConfig.AppProperties;
import com.filetransfer.entity.FileChunkEntity;
import com.filetransfer.entity.FileEntity;
import com.filetransfer.exception.InvalidFileStateException;
import com.filetransfer.repository.ChunkRepository;
import com.filetransfer.repository.FileRepository;
import com.filetransfer.util.ChecksumUtil;
import com.filetransfer.util.StorageUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkService {

    private final ChunkRepository chunkRepository;
    private final FileRepository  fileRepository;
    private final AppProperties   props;
    private final Path            storagePath;

    /**
     * Persist a single chunk to disk and record it in the database.
     *
     * Design decisions:
     * 1. We write to a temp file first, then atomically move it into place.
     *    This prevents partially-written chunks from appearing valid.
     * 2. MD5 is computed while streaming — zero extra reads.
     * 3. The file's uploadedChunks counter is incremented atomically via a
     *    targeted UPDATE, not a read-modify-write, to avoid lost updates
     *    under concurrent chunk uploads.
     * 4. If the chunk already exists (duplicate upload / retry), we skip it
     *    and return the existing record idempotently.
     */
    @Transactional
    public FileChunkEntity uploadChunk(UUID fileId,
                                       int chunkIndex,
                                       MultipartFile data,
                                       String declaredMd5) throws IOException {

        // 1. Load and validate the parent file
        FileEntity file = fileRepository.findById(fileId)
            .orElseThrow(() -> new com.filetransfer.exception.FileNotFoundException(fileId));

        if (file.getStatus() != FileEntity.Status.UPLOADING) {
            throw new InvalidFileStateException(
                "File " + fileId + " is not in UPLOADING state: " + file.getStatus());
        }

        validateChunkIndex(chunkIndex, file.getTotalChunks());

        // 2. Idempotency: if this chunk was already uploaded successfully, skip
        return chunkRepository.findByFileIdAndChunkIndex(fileId, chunkIndex)
            .filter(c -> c.getStatus() == FileChunkEntity.Status.UPLOADED)
            .orElseGet(() -> persistChunk(file, chunkIndex, data, declaredMd5));
    }

    /**
     * Return the list of already-uploaded chunk indexes.
     * The client uses this on reconnect to skip chunks it already sent.
     */
    public List<Integer> getUploadedChunkIndexes(UUID fileId) {
        return chunkRepository.findUploadedIndexesByFileId(fileId);
    }

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * Core write path: stream → temp file → compute MD5 → atomic move → DB record.
     * NOT marked @Transactional because the I/O happens outside the DB transaction.
     * The DB record is written only after the file is safely on disk.
     */
    private FileChunkEntity persistChunk(FileEntity file,
                                          int chunkIndex,
                                          MultipartFile data,
                                          String declaredMd5) {
        UUID fileId = file.getId();
        try {
            // Resolve the final destination path
            Path dest = StorageUtil.chunkPath(storagePath, fileId, chunkIndex);

            // Write to a sibling temp file first
            Path temp = dest.resolveSibling("chunk_" + chunkIndex + ".tmp");

            // Stream to temp, computing MD5 as we go
            String actualMd5;
            try (InputStream in = data.getInputStream()) {
                // We need to compute MD5 AND write the file. Do both in one pass.
                actualMd5 = streamToFileWithMd5(in, temp);
            }

            // Validate MD5 if the client supplied one
            if (declaredMd5 != null && !declaredMd5.isBlank()) {
                if (!actualMd5.equalsIgnoreCase(declaredMd5)) {
                    Files.deleteIfExists(temp);
                    throw new com.filetransfer.exception.ChecksumMismatchException(
                        "Chunk " + chunkIndex + " MD5 mismatch: expected=" + declaredMd5 + " actual=" + actualMd5);
                }
            }

            // Atomic move: temp → dest
            Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            long chunkSize = Files.size(dest);
            long byteOffset = (long) chunkIndex * props.chunkSizeBytes();

            // Build and save the chunk record
            FileChunkEntity chunk = buildChunkEntity(file, chunkIndex, chunkSize, byteOffset, actualMd5, dest);
            chunk = chunkRepository.save(chunk);

            // Atomic counter increment — avoids lost updates under concurrent uploads
            fileRepository.incrementUploadedChunks(fileId);

            log.debug("Chunk saved: fileId={} index={} size={} md5={}", fileId, chunkIndex, chunkSize, actualMd5);
            return chunk;

        } catch (IOException e) {
            log.error("Failed to store chunk {}/{}: {}", fileId, chunkIndex, e.getMessage());
            throw new RuntimeException("Chunk storage failed: " + e.getMessage(), e);
        }
    }

    /**
     * Stream an InputStream to disk while computing its MD5.
     * Uses an 8 KB buffer — small enough to stay in L1 cache, large enough to
     * avoid excessive system calls.
     */
    private String streamToFileWithMd5(InputStream in, Path dest) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8 * 1024];
            int read;
            try (var out = Files.newOutputStream(dest)) {
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    md.update(buf, 0, read);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private FileChunkEntity buildChunkEntity(FileEntity file, int chunkIndex,
                                              long chunkSize, long byteOffset,
                                              String md5, Path storedAt) {
        FileChunkEntity chunk = new FileChunkEntity();
        chunk.setFile(file);
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkSize(chunkSize);
        chunk.setByteOffset(byteOffset);
        chunk.setChecksumMd5(md5);
        chunk.setStoragePath(storedAt.toString());
        chunk.setStatus(FileChunkEntity.Status.UPLOADED);
        chunk.setUploadedAt(Instant.now());
        return chunk;
    }

    private void validateChunkIndex(int chunkIndex, int totalChunks) {
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException(
                "chunkIndex " + chunkIndex + " out of range [0, " + (totalChunks - 1) + "]");
        }
    }
}