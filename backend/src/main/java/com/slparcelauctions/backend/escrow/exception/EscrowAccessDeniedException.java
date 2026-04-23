package com.slparcelauctions.backend.escrow.exception;

/**
 * Thrown when an authenticated caller who is neither the seller nor the
 * winner attempts to view or mutate an escrow. Mapped to
 * {@code 403 ESCROW_FORBIDDEN} by {@link EscrowExceptionHandler}. The
 * message is deliberately generic so the response does not leak the
 * existence / identity of the real parties.
 */
public class EscrowAccessDeniedException extends RuntimeException {

    public EscrowAccessDeniedException() {
        super("Only the seller or winner may view or act on this escrow");
    }
}
