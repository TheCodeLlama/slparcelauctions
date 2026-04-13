package com.slparcelauctions.backend.verification.exception;

import lombok.Getter;

/**
 * Thrown when {@code consume} finds no matching row. Covers not-found, expired,
 * and already-used cases — the caller's remediation is identical (regenerate),
 * so there is one exception, not three.
 */
@Getter
public class CodeNotFoundException extends RuntimeException {
    private final String code;

    public CodeNotFoundException(String code) {
        super("Verification code not found, expired, or already used.");
        this.code = code;
    }
}
