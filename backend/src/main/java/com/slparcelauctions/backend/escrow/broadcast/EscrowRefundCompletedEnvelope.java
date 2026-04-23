package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;

/**
 * WebSocket envelope broadcast when a REFUND command completes successfully
 * (spec §8). No state change accompanies the callback — the refund flows are
 * queued from {@code DISPUTED} / {@code FROZEN} / {@code EXPIRED} transitions
 * which have already landed the escrow in its terminal state. The envelope
 * exists so subscribers can tell "the L$ have been returned to the winner"
 * separately from "the escrow entered this terminal state".
 *
 * <p>{@code refundAmount} is included because it is already known to the
 * winner (they are the recipient) — the privacy exclusion on
 * {@link EscrowCompletedEnvelope} applies to the seller's payout.
 */
public record EscrowRefundCompletedEnvelope(
        String type,
        Long auctionId,
        Long escrowId,
        EscrowState state,
        Long refundAmount,
        OffsetDateTime serverTime) implements EscrowEnvelope {

    public static EscrowRefundCompletedEnvelope of(Escrow e, long refundAmount,
            OffsetDateTime serverTime) {
        return new EscrowRefundCompletedEnvelope(
                "ESCROW_REFUND_COMPLETED",
                e.getAuction().getId(),
                e.getId(),
                e.getState(),
                refundAmount,
                serverTime);
    }
}
