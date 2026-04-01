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
import com.filetransfer.service.progress.ProgressBroadcaster;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShareService {

    /**
     * Avoid visually ambiguous characters:
     * O/0, I/1, l/1 are excluded.
     */
    private static final String ALPHANUM = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    /**
     * 64 KB gives good throughput without excessive memory use.
     */
    private static final int STREAM_BUFFER = 64 * 1024;

    /**
     * Broadcast progress every 512 KB to avoid spamming websocket clients.
     */
    private static final long PROGRESS_INTERVAL = 512L * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareCodeRepository shareCodeRepository;
    private final FileRepository fileRepository;
    private final TransferSessionRepository sessionRepository;
    private final AppProperties props;
    private final PasswordEncoder passwordEncoder;
    private final ProgressBroadcaster progressBroadcaster;

    /**
     * Create a share code for a READY file.
     *
     * Supports:
     * - Expiry time
     * - Max downloads
     * - Optional password protection
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

        int expiryHours = (req != null && req.getExpiryHours() != null)
                ? req.getExpiryHours()
                : props.defaultExpiryHours();

        if (expiryHours > 0) {
            share.setExpiresAt(Instant.now().plusSeconds(expiryHours * 3600L));
        }

        if (req != null
                && req.getPassword() != null
                && !req.getPassword().isBlank()) {

            share.setPasswordProtected(true);
            share.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        }

        ShareCodeEntity saved = shareCodeRepository.save(share);

        log.info(
                "Share code created: code={} fileId={} expiresAt={} passwordProtected={}",
                code,
                fileId,
                share.getExpiresAt(),
                share.isPasswordProtected());

        return saved;
    }

    /**
     * Resolve a share code into its metadata.
     */
    public ShareCodeEntity resolveCode(String code) {
        ShareCodeEntity share = shareCodeRepository.findByCode(code)
                .orElseThrow(() -> new InvalidShareCodeException("Code not found: " + code));

        if (!share.isValid()) {
            throw new InvalidShareCodeException(
                    "Share code is expired or download limit reached");
        }

        return share;
    }

    /**
     * Verifies password for password-protected share links.
     *
     * Uses BCrypt constant-time comparison to avoid timing attacks.
     */
    public void verifyPassword(ShareCodeEntity share, String rawPassword) {
        if (!share.isPasswordProtected()) {
            return;
        }

        if (rawPassword == null
                || rawPassword.isBlank()
                || !passwordEncoder.matches(rawPassword, share.getPasswordHash())) {

            // Intentionally vague: do not reveal whether code or password was wrong
            throw new InvalidShareCodeException("Invalid share code or password");
        }
    }

    /**
     * Streams a file to the client with HTTP Range support.
     *
     * Supports:
     * - Resume download
     * - Browser seeking
     * - Large files
     * - Download progress notifications
     */
    @Transactional
    public void streamFile(
            String code,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        ShareCodeEntity share = resolveCode(code);
        FileEntity file = share.getFile();
        Path filePath = Paths.get(file.getStoragePath());

        if (!Files.exists(filePath)) {
            throw new FileNotFoundException(
                    "Storage path missing for file: " + file.getId());
        }

        long fileSize = Files.size(filePath);

        // ── Parse Range header ──────────────────────────────────────────────
        String rangeHeader = request.getHeader("Range");

        long startByte = 0;
        long endByte = fileSize - 1;
        boolean isPartial = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            isPartial = true;

            try {
                String[] parts = rangeHeader.substring(6).split("-");

                if (!parts[0].isBlank()) {
                    startByte = Long.parseLong(parts[0]);
                }

                if (parts.length > 1 && !parts[1].isBlank()) {
                    endByte = Long.parseLong(parts[1]);
                }
            } catch (NumberFormatException ex) {
                throw new InvalidShareCodeException(
                        "Invalid Range header: " + rangeHeader);
            }
        }

        // Clamp range to valid file boundaries
        startByte = Math.max(0, startByte);
        endByte = Math.min(endByte, fileSize - 1);

        if (startByte > endByte) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader("Content-Range", "bytes */" + fileSize);
            return;
        }

        long contentLength = endByte - startByte + 1;

        // ── Response headers ────────────────────────────────────────────────
        response.setHeader(
                "Content-Type",
                Optional.ofNullable(file.getMimeType())
                        .orElse("application/octet-stream"));

        response.setHeader("Content-Length", String.valueOf(contentLength));
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"" + file.getOriginalName() + "\"");

        if (isPartial) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(
                    "Content-Range",
                    "bytes " + startByte + "-" + endByte + "/" + fileSize);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }

        TransferSessionEntity session = createDownloadSession(file, request);

        long transferred = 0;
        long nextBroadcast = PROGRESS_INTERVAL;
        long transferStartMs = System.currentTimeMillis();

        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r");
                OutputStream out = response.getOutputStream()) {

            raf.seek(startByte);

            byte[] buffer = new byte[STREAM_BUFFER];
            long remaining = contentLength;

            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);

                int read = raf.read(buffer, 0, toRead);

                if (read == -1) {
                    break;
                }

                out.write(buffer, 0, read);

                transferred += read;
                remaining -= read;

                // Broadcast progress every configured interval
                if (transferred >= nextBroadcast) {
                    progressBroadcaster.downloadProgress(
                            file.getId(),
                            read,
                            transferred,
                            contentLength);

                    log.debug(
                            "Download progress fileId={} transferred={} total={}",
                            file.getId(),
                            transferred,
                            contentLength);

                    nextBroadcast += PROGRESS_INTERVAL;
                }
            }

            out.flush();

        } catch (IOException e) {
            log.error(
                    "Download failed for code={} fileId={} transferred={} reason={}",
                    code,
                    file.getId(),
                    transferred,
                    e.getMessage(),
                    e);

            progressBroadcaster.downloadFailed(file.getId(), e.getMessage());
            throw e;

        } finally {
            finaliseSession(session, transferred, transferStartMs);
        }

        // Final progress event in case last chunk was smaller than interval
        progressBroadcaster.downloadProgress(
                file.getId(),
                0,
                transferred,
                contentLength);

        progressBroadcaster.downloadComplete(file.getId(), transferred);

        shareCodeRepository.incrementDownloadCount(share.getId());

        log.info(
                "Download complete: code={} fileId={} bytes={} partial={}",
                code,
                file.getId(),
                transferred,
                isPartial);
    }

    // ────────────────────────────────────────────────────────────────────────

    private String generateUniqueCode(int length) {
        for (int attempt = 1; attempt <= 10; attempt++) {
            StringBuilder code = new StringBuilder(length);

            for (int i = 0; i < length; i++) {
                code.append(ALPHANUM.charAt(
                        RANDOM.nextInt(ALPHANUM.length())));
            }

            String generated = code.toString();

            if (!shareCodeRepository.existsByCode(generated)) {
                return generated;
            }

            log.warn(
                    "Share code collision on attempt {} for generated code={}",
                    attempt,
                    generated);
        }

        throw new IllegalStateException(
                "Unable to generate a unique share code after 10 attempts");
    }

    private TransferSessionEntity createDownloadSession(
            FileEntity file,
            HttpServletRequest request) {
        TransferSessionEntity session = new TransferSessionEntity();

        session.setFile(file);
        session.setDirection(TransferSessionEntity.Direction.DOWNLOAD);
        session.setSessionType(TransferSessionEntity.Type.SERVER);
        session.setStatus(TransferSessionEntity.Status.ACTIVE);
        session.setClientIp(request.getRemoteAddr());
        session.setUserAgent(request.getHeader("User-Agent"));

        TransferSessionEntity saved = sessionRepository.save(session);

        log.debug(
                "Download session started: sessionId={} fileId={}",
                saved.getId(),
                file.getId());

        return saved;
    }

    private void finaliseSession(
            TransferSessionEntity session,
            long bytesTransferred,
            long startMs) {
        try {
            long elapsedMs = System.currentTimeMillis() - startMs;

            session.setBytesTransferred(bytesTransferred);
            session.setCompletedAt(Instant.now());

            if (elapsedMs > 0) {
                session.setStatus(TransferSessionEntity.Status.COMPLETED);
                session.setAvgSpeedBps(
                        (bytesTransferred * 1000.0) / elapsedMs);
            } else {
                session.setStatus(TransferSessionEntity.Status.FAILED);
            }

            sessionRepository.save(session);

            log.debug(
                    "Download session completed: sessionId={} bytes={} durationMs={} avgSpeed={} Bps",
                    session.getId(),
                    bytesTransferred,
                    elapsedMs,
                    session.getAvgSpeedBps());

        } catch (Exception e) {
            log.warn(
                    "Could not finalise session {}: {}",
                    session.getId(),
                    e.getMessage());
        }
    }
}
