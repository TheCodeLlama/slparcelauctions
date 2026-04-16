package com.slparcelauctions.backend.user.exception;

import lombok.Getter;

@Getter
public class InvalidAvatarSizeException extends RuntimeException {
    private final int requestedSize;

    public InvalidAvatarSizeException(int requestedSize) {
        super("Invalid avatar size: " + requestedSize + ". Must be 64, 128, or 256.");
        this.requestedSize = requestedSize;
    }
}
