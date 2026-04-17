package com.slparcelauctions.backend.user.exception;

/**
 * Byte size exceeds the configured maximum. Subclass of UnsupportedImageFormatException
 * so existing user-package handlers still catch it, while auction handlers can
 * instanceof-check it to return 413 instead of 400.
 */
public class ImageTooLargeException extends UnsupportedImageFormatException {
    public ImageTooLargeException(String message) {
        super(message);
    }
}
