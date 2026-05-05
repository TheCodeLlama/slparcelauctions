package com.slparcelauctions.backend.wallet.exception;

/**
 * Thrown when an admin-frozen user attempts any wallet outflow (withdraw,
 * pay-penalty, pay-listing-fee, bid reservation). Inflows (deposits, admin
 * adjustments) are not blocked.
 *
 * <p>Maps to 423 {@code WALLET_FROZEN} on the JSON-API surface; the SL
 * terminal endpoints translate to a REFUND response.
 */
public class WalletFrozenException extends RuntimeException {
    private final Long userId;

    public WalletFrozenException(Long userId) {
        super("wallet is frozen: userId=" + userId);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
