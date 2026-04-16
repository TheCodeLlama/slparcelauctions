package com.slparcelauctions.backend.user.exception;

import lombok.Getter;

@Getter
public class AvatarTooLargeException extends RuntimeException {
    private final long actualBytes;
    private final long maxBytes;

    public AvatarTooLargeException(long actualBytes, long maxBytes) {
        super("Avatar too large: " + actualBytes + " bytes exceeds limit of " + maxBytes);
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }
}
