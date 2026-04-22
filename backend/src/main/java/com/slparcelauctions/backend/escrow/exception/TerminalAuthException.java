package com.slparcelauctions.backend.escrow.exception;

/**
 * Thrown by {@code TerminalService.assertSharedSecret} and the SL-facing
 * escrow endpoints when the {@code sharedSecret} body field on an in-world
 * callback does not match the configured {@code slpa.escrow.terminal-shared-secret}.
 * Mapped to HTTP 403 with {@code code=SECRET_MISMATCH} by
 * {@link EscrowExceptionHandler}.
 */
public class TerminalAuthException extends RuntimeException {
    public TerminalAuthException() {
        super("Terminal shared secret mismatch");
    }
}
