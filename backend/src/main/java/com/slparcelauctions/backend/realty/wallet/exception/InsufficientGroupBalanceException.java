package com.slparcelauctions.backend.realty.wallet.exception;

import lombok.Getter;

/**
 * Thrown when a group wallet operation requires more L$ than the group's
 * available balance. Maps to 422 UNPROCESSABLE_ENTITY. Spec §5.5.
 */
@Getter
public class InsufficientGroupBalanceException extends RuntimeException {

    private final long available;
    private final long requested;

    public InsufficientGroupBalanceException(long available, long requested) {
        super("group balance insufficient: available=" + available + " requested=" + requested);
        this.available = available;
        this.requested = requested;
    }
}
