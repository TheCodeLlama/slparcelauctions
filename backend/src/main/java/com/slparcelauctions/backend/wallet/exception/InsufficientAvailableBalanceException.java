package com.slparcelauctions.backend.wallet.exception;

/**
 * Thrown when a wallet operation would require more L$ than the user has
 * available ({@code balance_lindens - reserved_lindens}). Maps to a 422
 * {@code INSUFFICIENT_AVAILABLE_BALANCE} response on user-facing endpoints
 * and {@code REFUND_BLOCKED/INSUFFICIENT_BALANCE} on the SL-headers withdraw
 * endpoint.
 */
public class InsufficientAvailableBalanceException extends RuntimeException {
    private final long available;
    private final long requested;

    public InsufficientAvailableBalanceException(long available, long requested) {
        super("insufficient available balance: available=" + available
                + ", requested=" + requested);
        this.available = available;
        this.requested = requested;
    }

    public long getAvailable() {
        return available;
    }

    public long getRequested() {
        return requested;
    }
}
