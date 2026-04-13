package com.slparcelauctions.backend.verification.exception;

import lombok.Getter;

@Getter
public class AlreadyVerifiedException extends RuntimeException {
    private final Long userId;

    public AlreadyVerifiedException(Long userId) {
        super("User " + userId + " is already SL-verified.");
        this.userId = userId;
    }
}
