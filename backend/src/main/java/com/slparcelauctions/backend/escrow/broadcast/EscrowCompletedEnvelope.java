package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;

/**
 * WebSocket envelope broadcast when the terminal's payout-result callback
 * flips an escrow from {@link EscrowState#TRANSFER_PENDING} to
 * {@link EscrowState#COMPLETED} (spec §8). Published afterCommit by
 * {@code TerminalCommandService.applyCallback} so subscribers never observe
 * a completion that gets rolled back on a late DB failure.
 *
 * <p>No monetary fields on the wire — the payout / commission amounts live
 * on the escrow row itself and on the ledger (spec §8 privacy rule: the
 * winner should not be able to derive the seller's net from a broadcast).
 * Clients that need amounts re-fetch via the authenticated
 * {@code GET /api/v1/auctions/{id}/escrow} endpoint.
 */
public record EscrowCompletedEnvelope(
        String type,
        UUID auctionPublicId,
        UUID escrowPublicId,
        EscrowState state,
        OffsetDateTime completedAt,
        OffsetDateTime serverTime) implements EscrowEnvelope {

    public static EscrowCompletedEnvelope of(Escrow e, OffsetDateTime serverTime) {
        return new EscrowCompletedEnvelope(
                "ESCROW_COMPLETED",
                e.getAuction().getPublicId(),
                e.getPublicId(),
                e.getState(),
                e.getCompletedAt(),
                serverTime);
    }
}
