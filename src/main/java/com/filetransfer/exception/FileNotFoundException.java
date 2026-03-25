package com.filetransfer.exception;

// ─── File not found ───────────────────────────────────────────────────────────
// Thrown when a fileId doesn't exist or has been deleted.

public class FileNotFoundException extends RuntimeException {
    public FileNotFoundException(String message) { super(message); }
    public FileNotFoundException(java.util.UUID id) {
        super("File not found: " + id);
    }
}