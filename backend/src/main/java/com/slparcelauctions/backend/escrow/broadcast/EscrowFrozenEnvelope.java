package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;

/**
 * WebSocket envelope broadcast when the ownership monitor (or any other
 * system-triggered freeze path) transitions an escrow to
 * {@link EscrowState#FROZEN} (spec §4.5). Published afterCommit by
 * {@code EscrowService.freezeForFraud} so subscribers never observe a freeze
 * that gets rolled back.
 *
 * <p>{@code reason} is the {@link com.slparcelauctions.backend.escrow.FreezeReason}
 * enum name copied off {@link Escrow#getFreezeReason()} — clients render a
 * human-friendly message from the enum value.
 */
public record EscrowFrozenEnvelope(
        String type,
        Long auctionId,
        Long escrowId,
        EscrowState state,
        String reason,
        OffsetDateTime serverTime) implements EscrowEnvelope {

    public static EscrowFrozenEnvelope of(Escrow e, OffsetDateTime serverTime) {
        return new EscrowFrozenEnvelope(
                "ESCROW_FROZEN",
                e.getAuction().getId(),
                e.getId(),
                e.getState(),
                e.getFreezeReason(),
                serverTime);
    }
}
