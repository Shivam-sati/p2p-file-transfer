package com.filetransfer.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts exceptions to RFC 9457 ProblemDetail responses.
 * Every error body has: type, title, status, detail, timestamp.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── 404 ──────────────────────────────────────────────────────────────────

    @ExceptionHandler(FileNotFoundException.class)
    public ProblemDetail handleFileNotFound(FileNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "file-not-found", ex.getMessage());
    }

    @ExceptionHandler(InvalidShareCodeException.class)
    public ProblemDetail handleInvalidShareCode(InvalidShareCodeException ex) {
        return problem(HttpStatus.NOT_FOUND, "invalid-share-code", ex.getMessage());
    }

    // ── 409 Conflict ─────────────────────────────────────────────────────────

    @ExceptionHandler(InvalidFileStateException.class)
    public ProblemDetail handleInvalidState(InvalidFileStateException ex) {
        return problem(HttpStatus.CONFLICT, "invalid-file-state", ex.getMessage());
    }

    // ── 422 Unprocessable ────────────────────────────────────────────────────

    @ExceptionHandler(ChecksumMismatchException.class)
    public ProblemDetail handleChecksumMismatch(ChecksumMismatchException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "checksum-mismatch", ex.getMessage());
    }

    @ExceptionHandler(ChunkMissingException.class)
    public ProblemDetail handleChunkMissing(ChunkMissingException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "chunk-missing", ex.getMessage());
    }

    // ── 400 Validation ───────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage,
                                      (a, b) -> a));  // keep first on duplicate keys
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation-error", "Request validation failed");
        pd.setProperty("fields", fields);
        return pd;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleFileTooLarge(MaxUploadSizeExceededException ex) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "chunk-too-large",
            "Chunk exceeds the maximum allowed size");
    }

    // ── 500 Fallback ─────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
            "An unexpected error occurred");
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://filetransfer.io/errors/" + type));
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}