package com.slparcelauctions.backend.realty.wallet.exception;

/**
 * Sub-project G section 7.2 -- admin-initiated wallet adjustment exceeded the
 * sanity ceiling configured by {@code slpa.realty.admin-wallet-adjust-max-l}.
 * Surfaced as 422 by {@code RealtyExceptionHandler} so the admin UI can show a
 * clear "adjustment too large" error and operators can dial the ceiling up via
 * config if a legitimate large adjustment is ever blocked.
 *
 * <p>The ceiling is a fat-finger guardrail, not a prod policy lever -- the
 * actual policy lever is admin review at adjustment time.
 */
public class AdminAdjustAmountOutOfRangeException extends RuntimeException {

    private final long amount;
    private final long ceiling;

    public AdminAdjustAmountOutOfRangeException(long amount, long ceiling) {
        super("amount " + amount + " exceeds configured ceiling " + ceiling);
        this.amount = amount;
        this.ceiling = ceiling;
    }

    public long getAmount() {
        return amount;
    }

    public long getCeiling() {
        return ceiling;
    }
}
