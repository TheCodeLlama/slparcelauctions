package com.slparcelauctions.backend.realty.wallet.exception;

/**
 * Thrown when a withdrawal is initiated but the group leader's user account is
 * BANNED or FROZEN, which blocks the receiving leg of the L$ transfer.
 * Maps to 422 UNPROCESSABLE_ENTITY. Spec §5.5.
 */
public class LeaderFrozenException extends RuntimeException {

    public LeaderFrozenException() {
        super("group leader status blocks withdrawal");
    }
}
