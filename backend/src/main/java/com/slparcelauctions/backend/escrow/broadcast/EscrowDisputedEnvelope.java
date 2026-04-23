package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;

/**
 * WebSocket envelope broadcast when an escrow transitions to
 * {@link EscrowState#DISPUTED}. Published after commit by
 * {@code EscrowService.fileDispute} so subscribers never see a row that
 * gets rolled back with the dispute transaction. The {@code reason} is
 * the stored dispute category name — the free-text description is not
 * broadcast over the public topic. Spec §4.4 / §8.
 */
public record EscrowDisputedEnvelope(
        String type,
        Long auctionId,
        Long escrowId,
        EscrowState state,
        String reason,
        OffsetDateTime serverTime) implements EscrowEnvelope {

    public static EscrowDisputedEnvelope of(Escrow e, OffsetDateTime serverTime) {
        return new EscrowDisputedEnvelope(
                "ESCROW_DISPUTED",
                e.getAuction().getId(),
                e.getId(),
                e.getState(),
                e.getDisputeReasonCategory(),
                serverTime);
    }
}
