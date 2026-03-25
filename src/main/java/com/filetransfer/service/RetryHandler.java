package com.filetransfer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Generic exponential-backoff retry handler.
 *
 * Strategy:
 *   attempt 1 → immediate
 *   attempt 2 → wait 1s
 *   attempt 3 → wait 2s
 *   attempt 4 → wait 4s
 *   ...up to maxAttempts
 *
 * Jitter (±20%) is added to each wait to avoid thundering-herd when
 * many uploads are retrying simultaneously after a transient failure.
 *
 * Usage:
 *   RetryHandler.retry(3, () -> chunkService.uploadChunk(...));
 */
@Component
@Slf4j
public class RetryHandler {

    private static final long BASE_DELAY_MS = 1_000;
    private static final long MAX_DELAY_MS  = 30_000;  // cap at 30 s
    private static final double JITTER      = 0.2;     // ±20%

    /**
     * Execute the supplier with up to {@code maxAttempts} tries.
     * Retries on any exception except those in the no-retry list.
     */
    public static <T> T retry(int maxAttempts, String operationName, Supplier<T> operation) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = operation.get();
                if (attempt > 1) {
                    log.info("'{}' succeeded on attempt {}", operationName, attempt);
                }
                return result;
            } catch (Exception e) {
                lastException = e;

                // Don't retry on errors that are clearly not transient
                if (isNonRetryable(e)) {
                    log.warn("'{}' failed with non-retryable error: {}", operationName, e.getMessage());
                    throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                }

                if (attempt < maxAttempts) {
                    long delay = computeDelay(attempt);
                    log.warn("'{}' failed (attempt {}/{}), retrying in {}ms: {}",
                        operationName, attempt, maxAttempts, delay, e.getMessage());
                    sleep(delay);
                } else {
                    log.error("'{}' failed after {} attempts: {}", operationName, maxAttempts, e.getMessage());
                }
            }
        }

        // All attempts exhausted
        throw (lastException instanceof RuntimeException re)
            ? re
            : new RuntimeException("Operation '" + operationName + "' failed after " + maxAttempts + " attempts", lastException);
    }

    /** Void variant — for operations that don't return a value. */
    public static void retryVoid(int maxAttempts, String operationName, Runnable operation) {
        retry(maxAttempts, operationName, () -> { operation.run(); return null; });
    }

    // ── private ──────────────────────────────────────────────────────────────

    private static long computeDelay(int attempt) {
        // 2^(attempt-1) * BASE_DELAY, capped at MAX_DELAY
        long exponential = BASE_DELAY_MS * (1L << (attempt - 1));
        long capped       = Math.min(exponential, MAX_DELAY_MS);
        // Add ±20% jitter
        double jitterFactor = 1.0 + (Math.random() * 2 * JITTER) - JITTER;
        return (long) (capped * jitterFactor);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /**
     * Errors where retrying is pointless — bad input, auth failures, etc.
     * Throwing these immediately saves time and avoids misleading retry logs.
     */
    private static boolean isNonRetryable(Exception e) {
        return e instanceof com.filetransfer.exception.ChecksumMismatchException
            || e instanceof com.filetransfer.exception.InvalidFileStateException
            || e instanceof IllegalArgumentException
            || e instanceof com.filetransfer.exception.FileNotFoundException;
    }
}