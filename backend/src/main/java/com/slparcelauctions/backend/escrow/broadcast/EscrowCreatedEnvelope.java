package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;

/**
 * WebSocket envelope broadcast when the escrow row is created for a just-
 * ended auction ({@code SOLD} or {@code BOUGHT_NOW} outcome). Emitted on
 * afterCommit so subscribers never observe a row that gets rolled back with
 * the auction close. Spec §8.
 */
public record EscrowCreatedEnvelope(
        String type,
        Long auctionId,
        Long escrowId,
        EscrowState state,
        OffsetDateTime paymentDeadline,
        OffsetDateTime serverTime) implements EscrowEnvelope {

    public static EscrowCreatedEnvelope of(Escrow e, OffsetDateTime serverTime) {
        return new EscrowCreatedEnvelope(
                "ESCROW_CREATED",
                e.getAuction().getId(),
                e.getId(),
                e.getState(),
                e.getPaymentDeadline(),
                serverTime);
    }
}
