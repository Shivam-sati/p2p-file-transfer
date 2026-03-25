package com.filetransfer.exception;

public class InvalidShareCodeException extends RuntimeException {
    public InvalidShareCodeException(String message) { super(message); }
}