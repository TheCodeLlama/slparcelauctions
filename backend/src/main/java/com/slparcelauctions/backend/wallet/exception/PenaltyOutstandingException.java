package com.slparcelauctions.backend.wallet.exception;

/**
 * Thrown when a user with an outstanding penalty attempts a new commitment
 * (bid, BIN, listing publish). Penalty does not block deposits, withdraws,
 * existing-commitment fulfillment, or paying the penalty itself.
 *
 * <p>Maps to 422 {@code PENALTY_OUTSTANDING} on the affected endpoints.
 */
public class PenaltyOutstandingException extends RuntimeException {
    private final long owed;

    public PenaltyOutstandingException(long owed) {
        super("user has outstanding penalty: owed=" + owed);
        this.owed = owed;
    }

    public long getOwed() {
        return owed;
    }
}
