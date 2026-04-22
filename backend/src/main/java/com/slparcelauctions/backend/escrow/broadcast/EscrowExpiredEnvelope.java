package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;

/**
 * WebSocket envelope broadcast when an escrow transitions to
 * {@link EscrowState#EXPIRED} by the scheduled timeout sweeper (spec §4.6).
 * Published after commit by
 * {@code EscrowService.expirePayment} / {@code expireTransfer} so subscribers
 * never see a row that gets rolled back with the expire transaction.
 *
 * <p>{@code reason} is a stable string discriminator — {@code PAYMENT_TIMEOUT}
 * when the winner never paid (no refund queued; no L$ was held) or
 * {@code TRANSFER_TIMEOUT} when the seller never transferred the parcel
 * (refund queued so the winner gets their L$ back).
 */
public record EscrowExpiredEnvelope(
        String type,
        Long auctionId,
        Long escrowId,
        EscrowState state,
        String reason,
        OffsetDateTime serverTime) implements EscrowEnvelope {

    public static EscrowExpiredEnvelope of(Escrow e, String reason, OffsetDateTime serverTime) {
        return new EscrowExpiredEnvelope(
                "ESCROW_EXPIRED",
                e.getAuction().getId(),
                e.getId(),
                e.getState(),
                reason,
                serverTime);
    }
}
