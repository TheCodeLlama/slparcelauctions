package com.slparcelauctions.backend.storage.exception;

public class ObjectStorageException extends RuntimeException {
    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
