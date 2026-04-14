package com.slparcelauctions.backend.verification.exception;

import java.util.List;

import lombok.Getter;

/**
 * Thrown when {@code consume} finds more than one row matching the code.
 * Expected statistical event (spec Q5b). Both rows are voided before the
 * exception is thrown. Maps to HTTP 409 with a "generate a new code" message.
 */
@Getter
public class CodeCollisionException extends RuntimeException {
    private final String code;
    private final List<Long> affectedUserIds;

    public CodeCollisionException(String code, List<Long> affectedUserIds) {
        super("Verification code collision: " + affectedUserIds.size() + " users.");
        this.code = code;
        this.affectedUserIds = List.copyOf(affectedUserIds);
    }
}
