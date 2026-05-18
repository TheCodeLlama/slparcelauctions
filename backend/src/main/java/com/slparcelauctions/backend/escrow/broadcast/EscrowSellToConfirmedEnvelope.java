package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;

/**
 * WebSocket envelope broadcast when the bot (or an admin force-confirm)
 * stamps {@code sellToConfirmedAt} on the escrow — the Set-Sell-To
 * sub-phase has cleared and the escrow advances to Buy Parcel (spec §8).
 * Published afterCommit by {@code EscrowService.confirmSellTo} so
 * subscribers never observe a confirmation that gets rolled back.
 *
 * <p>State on the wire stays {@link EscrowState#TRANSFER_PENDING} — the
 * sub-phase is derived from timestamps, not the {@code EscrowState} enum.
 * Clients infer "Sell To confirmed" from the {@code type} discriminator
 * and the presence of {@code sellToConfirmedAt}.
 */
public record EscrowSellToConfirmedEnvelope(
        String type,
        UUID auctionPublicId,
        UUID escrowPublicId,
        EscrowState state,
        OffsetDateTime sellToConfirmedAt,
        OffsetDateTime serverTime) implements EscrowEnvelope {

    public static EscrowSellToConfirmedEnvelope of(Escrow e, OffsetDateTime serverTime) {
        return new EscrowSellToConfirmedEnvelope(
                "ESCROW_SELL_TO_SET",
                e.getAuction().getPublicId(),
                e.getPublicId(),
                e.getState(),
                e.getSellToConfirmedAt(),
                serverTime);
    }
}
