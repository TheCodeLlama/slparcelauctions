package com.slparcelauctions.backend.escrow.exception;

/**
 * Thrown when a seller/buyer has consumed all of their manual verify
 * attempts (cap = {@code slpa.escrow.manual-verify-attempts}, default 3)
 * for the current escrow step. Mapped to {@code 409 ESCROW_MANUAL_ATTEMPTS_EXHAUSTED}
 * by {@link EscrowExceptionHandler}; the frontend surfaces the
 * "request manual review" escalation hint when it sees this.
 */
public class EscrowManualAttemptsExhaustedException extends RuntimeException {

    public EscrowManualAttemptsExhaustedException(String step) {
        super("Manual " + step + " verify attempts exhausted; request a manual review");
    }
}
