package com.slparcelauctions.backend.escrow.command.exception;

import lombok.Getter;

@Getter
public class UnknownTerminalCommandException extends RuntimeException {
    private final String idempotencyKey;

    public UnknownTerminalCommandException(String idempotencyKey) {
        super("No terminal command found for idempotencyKey=" + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }
}
