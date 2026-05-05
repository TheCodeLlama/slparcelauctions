package com.slparcelauctions.backend.wallet.exception;

import java.util.UUID;

/**
 * Thrown by {@code WalletService.deposit} when the SL avatar UUID who paid
 * the terminal does not match a verified SLParcels user. The terminal-side
 * response is {@code REFUND/UNKNOWN_PAYER} — the LSL script bounces the L$
 * via {@code llTransferLindenDollars}.
 */
public class UserNotLinkedException extends RuntimeException {
    private final UUID payerUuid;

    public UserNotLinkedException(UUID payerUuid) {
        super("no SLParcels user linked to SL avatar UUID " + payerUuid);
        this.payerUuid = payerUuid;
    }

    public UUID getPayerUuid() {
        return payerUuid;
    }
}
