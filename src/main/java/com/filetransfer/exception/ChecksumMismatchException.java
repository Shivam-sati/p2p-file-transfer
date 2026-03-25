package com.filetransfer.exception;

public class ChecksumMismatchException extends RuntimeException {
    public ChecksumMismatchException(String message) { super(message); }
}