package com.filetransfer.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Thread-safe checksum utilities.
 * MessageDigest instances are NOT thread-safe, so we create a new one per call.
 */
@Slf4j
public final class ChecksumUtil {

    private static final int BUFFER_SIZE = 8 * 1024; // 8 KB read buffer

    private ChecksumUtil() {}

    /**
     * Compute SHA-256 of a file on disk by streaming it — never loads the whole file.
     */
    public static String sha256(Path filePath) throws IOException {
        return digest(filePath, "SHA-256");
    }

    /**
     * Compute MD5 of a MultipartFile chunk as it streams in.
     * We wrap the input stream so we digest while writing — zero extra reads.
     */
    public static String md5(InputStream inputStream) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[BUFFER_SIZE];
            try (DigestInputStream dis = new DigestInputStream(inputStream, md)) {
                while (dis.read(buf) != -1) { /* drain */ }
            }
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    /**
     * Verify that a file's SHA-256 matches the expected value.
     * Throws ChecksumMismatchException on failure so callers don't need to branch.
     */
    public static void verifySha256(Path filePath, String expected) throws IOException {
        String actual = sha256(filePath);
        if (!actual.equalsIgnoreCase(expected)) {
            log.error("Checksum mismatch for {}: expected={} actual={}", filePath, expected, actual);
            throw new com.filetransfer.exception.ChecksumMismatchException(
                "SHA-256 mismatch: expected " + expected + " got " + actual
            );
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private static String digest(Path path, String algorithm) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[BUFFER_SIZE];
            try (InputStream is = Files.newInputStream(path);
                 DigestInputStream dis = new DigestInputStream(is, md)) {
                while (dis.read(buf) != -1) { /* drain */ }
            }
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}