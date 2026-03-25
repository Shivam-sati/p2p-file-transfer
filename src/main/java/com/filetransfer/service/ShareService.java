package com.filetransfer.service;

import com.filetransfer.config.AppConfig.AppProperties;
import com.filetransfer.dto.request.ShareCreateRequest;
import com.filetransfer.entity.FileEntity;
import com.filetransfer.entity.ShareCodeEntity;
import com.filetransfer.entity.TransferSessionEntity;
import com.filetransfer.exception.FileNotFoundException;
import com.filetransfer.exception.InvalidFileStateException;
import com.filetransfer.exception.InvalidShareCodeException;
import com.filetransfer.repository.FileRepository;
import com.filetransfer.repository.ShareCodeRepository;
import com.filetransfer.repository.TransferSessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShareService {

    private static final String ALPHANUM = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"; // no lookalike chars
    private static final int    STREAM_BUFFER = 64 * 1024; // 64 KB read buffer for downloads
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareCodeRepository       shareCodeRepository;
    private final FileRepository            fileRepository;
    private final TransferSessionRepository sessionRepository;
    private final AppProperties             props;

    /**
     * Generate a short alphanumeric code that links to a ready file.
     * Collisions are resolved by retrying (probability ~1/50^8 ≈ negligible).
     */
    @Transactional
    public ShareCodeEntity createShareCode(UUID fileId, ShareCreateRequest req) {
        FileEntity file = fileRepository.findById(fileId)
            .orElseThrow(() -> new FileNotFoundException(fileId));

        if (file.getStatus() != FileEntity.Status.READY) {
            throw new InvalidFileStateException(
                "File must be in READY state to share. Current: " + file.getStatus());
        }

        String code = generateUniqueCode(props.shareCodeLength());

        ShareCodeEntity share = new ShareCodeEntity();
        share.setFile(file);
        share.setCode(code);
        share.setMaxDownloads(req != null ? req.getMaxDownloads() : null);

        // Expiry: use request value, or fall back to server default, or no expiry if 0
        int hours = (req != null && req.getExpiryHours() != null)
            ? req.getExpiryHours()
            : props.defaultExpiryHours();
        if (hours > 0) {
            share.setExpiresAt(Instant.now().plusSeconds(hours * 3600L));
        }

        if (req != null && req.getPassword() != null && !req.getPassword().isBlank()) {
            // In production replace with BCrypt — kept simple here to avoid extra dependency
            share.setPasswordProtected(true);
            share.setPasswordHash(hashPassword(req.getPassword()));
        }

        ShareCodeEntity saved = shareCodeRepository.save(share);
        log.info("Share code created: code={} fileId={} expiresAt={}", code, fileId, share.getExpiresAt());
        return saved;
    }

    /**
     * Resolve a code → file metadata (without streaming the file).
     * Used by the frontend to show filename/size before downloading.
     */
    public ShareCodeEntity resolveCode(String code) {
        ShareCodeEntity share = shareCodeRepository.findByCode(code)
            .orElseThrow(() -> new InvalidShareCodeException("Code not found: " + code));

        if (!share.isValid()) {
            throw new InvalidShareCodeException("Share code is expired or download limit reached");
        }
        return share;
    }

    /**
     * Stream the file to the HTTP response, with Range header support.
     *
     * Range support allows:
     *  - Resumable downloads (browser pauses and resumes)
     *  - Parallel chunked downloads
     *  - Video/audio seeking in browser players
     *
     * We use RandomAccessFile.seek() + manual buffered copy rather than
     * Files.copy() because we need to honour arbitrary byte ranges.
     */
    @Transactional
    public void streamFile(String code, HttpServletRequest request, HttpServletResponse response)
        throws IOException {

        ShareCodeEntity share = resolveCode(code);
        FileEntity file = share.getFile();
        Path filePath = Paths.get(file.getStoragePath());

        if (!Files.exists(filePath)) {
            throw new FileNotFoundException("Storage path missing for file: " + file.getId());
        }

        long fileSize = Files.size(filePath);

        // ── Parse Range header ────────────────────────────────────────────────
        String rangeHeader = request.getHeader("Range");
        long startByte = 0;
        long endByte   = fileSize - 1;
        boolean isPartial = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            isPartial = true;
            String[] parts = rangeHeader.substring(6).split("-");
            startByte = Long.parseLong(parts[0]);
            endByte   = (parts.length > 1 && !parts[1].isBlank())
                ? Long.parseLong(parts[1])
                : fileSize - 1;
        }

        // Clamp to valid range
        endByte = Math.min(endByte, fileSize - 1);
        long contentLength = endByte - startByte + 1;

        // ── Set response headers ─────────────────────────────────────────────
        response.setHeader("Content-Type",        Optional.ofNullable(file.getMimeType()).orElse("application/octet-stream"));
        response.setHeader("Content-Length",       String.valueOf(contentLength));
        response.setHeader("Accept-Ranges",        "bytes");
        response.setHeader("Content-Disposition",  "attachment; filename=\"" + file.getOriginalName() + "\"");
        response.setHeader("Cache-Control",        "no-store");

        if (isPartial) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", "bytes " + startByte + "-" + endByte + "/" + fileSize);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }

        // ── Create download session record ───────────────────────────────────
        TransferSessionEntity session = createDownloadSession(file, request);

        // ── Stream the bytes ─────────────────────────────────────────────────
        long transferred = 0;
        long transferStart = System.currentTimeMillis();

        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r");
             OutputStream out = response.getOutputStream()) {

            raf.seek(startByte);
            byte[] buf = new byte[STREAM_BUFFER];
            long remaining = contentLength;

            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int read   = raf.read(buf, 0, toRead);
                if (read == -1) break;
                out.write(buf, 0, read);
                remaining    -= read;
                transferred  += read;
            }

            out.flush();

        } finally {
            // Always finalise the session record, even on partial/aborted downloads
            finaliseSession(session, transferred, transferStart);
        }

        // Increment download counter — atomic update in DB
        shareCodeRepository.incrementDownloadCount(share.getId());
        log.info("Download complete: code={} bytes={} partial={}", code, transferred, isPartial);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String generateUniqueCode(int length) {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
            }
            String code = sb.toString();
            if (!shareCodeRepository.existsByCode(code)) return code;
        }
        throw new IllegalStateException("Could not generate unique share code after 10 attempts");
    }

    private TransferSessionEntity createDownloadSession(FileEntity file, HttpServletRequest request) {
        TransferSessionEntity session = new TransferSessionEntity();
        session.setFile(file);
        session.setDirection(TransferSessionEntity.Direction.DOWNLOAD);
        session.setSessionType(TransferSessionEntity.Type.SERVER);
        session.setStatus(TransferSessionEntity.Status.ACTIVE);
        session.setClientIp(request.getRemoteAddr());
        session.setUserAgent(request.getHeader("User-Agent"));
        return sessionRepository.save(session);
    }

    private void finaliseSession(TransferSessionEntity session, long bytesTransferred, long startMs) {
        try {
            long elapsedMs = System.currentTimeMillis() - startMs;
            session.setBytesTransferred(bytesTransferred);
            session.setCompletedAt(Instant.now());
            session.setStatus(elapsedMs > 0
                ? TransferSessionEntity.Status.COMPLETED
                : TransferSessionEntity.Status.FAILED);
            if (elapsedMs > 0) {
                session.setAvgSpeedBps(bytesTransferred * 1000.0 / elapsedMs);
            }
            sessionRepository.save(session);
        } catch (Exception e) {
            log.warn("Could not finalise session {}: {}", session.getId(), e.getMessage());
        }
    }

    private String hashPassword(String password) {
        // TODO Phase 2: replace with BCryptPasswordEncoder
        // Using Base64 here only as a placeholder — not secure for production
        return Base64.getEncoder().encodeToString(
            (password + "SALT_REPLACE_ME").getBytes()
        );
    }
}