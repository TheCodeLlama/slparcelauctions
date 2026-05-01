package com.slparcelauctions.backend.wallet.exception;

/**
 * Thrown by {@code WalletService.payPenalty} when {@code amount} exceeds the
 * user's outstanding {@code penalty_balance_owed}. Partial payments are
 * permitted up to the owed amount.
 */
public class AmountExceedsOwedException extends RuntimeException {
    private final long owed;
    private final long requested;

    public AmountExceedsOwedException(long owed, long requested) {
        super("amount exceeds owed: owed=" + owed + ", requested=" + requested);
        this.owed = owed;
        this.requested = requested;
    }

    public long getOwed() {
        return owed;
    }

    public long getRequested() {
        return requested;
    }
}
