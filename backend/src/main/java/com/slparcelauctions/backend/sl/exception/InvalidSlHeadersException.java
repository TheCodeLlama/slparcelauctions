package com.slparcelauctions.backend.sl.exception;

/**
 * Thrown when the incoming SL request fails the {@code SlHeaderValidator}
 * checks (wrong shard, missing/malformed owner key, untrusted owner key).
 * Maps to HTTP 403.
 */
public class InvalidSlHeadersException extends RuntimeException {
    public InvalidSlHeadersException(String message) {
        super(message);
    }
}
