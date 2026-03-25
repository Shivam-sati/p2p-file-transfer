package com.filetransfer.exception;

public class ChunkMissingException extends RuntimeException {
    public ChunkMissingException(String message) { super(message); }
}