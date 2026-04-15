package com.slparcelauctions.backend.user.exception;

public class UnsupportedImageFormatException extends RuntimeException {
    public UnsupportedImageFormatException(String message) {
        super(message);
    }

    public UnsupportedImageFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
