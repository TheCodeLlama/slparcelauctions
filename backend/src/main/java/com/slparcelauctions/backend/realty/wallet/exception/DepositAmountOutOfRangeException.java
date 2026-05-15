package com.slparcelauctions.backend.realty.wallet.exception;

import lombok.Getter;

/**
 * Thrown by {@code RealtyGroupWalletService.depositFromMemberWallet} when the
 * requested deposit amount falls outside the configured
 * {@code slpa.realty.group-deposit-max-l} range (minimum L$1, maximum from
 * config, default L$500,000). Maps to a 400 {@code AMOUNT_OUT_OF_RANGE}
 * response on the user-facing deposit endpoint and REFUND {@code AMOUNT_OUT_OF_RANGE}
 * on the SL-headers in-world deposit endpoint.
 */
@Getter
public class DepositAmountOutOfRangeException extends RuntimeException {
    private final long min;
    private final long max;
    private final long requested;

    public DepositAmountOutOfRangeException(long min, long max, long requested) {
        super("amount " + requested + " out of range [" + min + ", " + max + "]");
        this.min = min;
        this.max = max;
        this.requested = requested;
    }
}
