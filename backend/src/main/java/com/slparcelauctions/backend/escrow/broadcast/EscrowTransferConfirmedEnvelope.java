package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;

/**
 * WebSocket envelope broadcast when the ownership monitor confirms the SL
 * parcel has transitioned to the auction winner (spec §4.5). Published
 * afterCommit by {@code EscrowService.confirmTransfer} so subscribers never
 * observe a confirmation that gets rolled back.
 *
 * <p>State on the wire stays {@link EscrowState#TRANSFER_PENDING} — only the
 * Task 7 payout callback flips to {@link EscrowState#COMPLETED}. Clients infer
 * "owner verified" from the {@code type} discriminator and the presence of
 * {@code transferConfirmedAt}.
 */
public record EscrowTransferConfirmedEnvelope(
        String type,
        Long auctionId,
        Long escrowId,
        EscrowState state,
        OffsetDateTime transferConfirmedAt,
        OffsetDateTime serverTime) implements EscrowEnvelope {

    public static EscrowTransferConfirmedEnvelope of(Escrow e, OffsetDateTime serverTime) {
        return new EscrowTransferConfirmedEnvelope(
                "ESCROW_TRANSFER_CONFIRMED",
                e.getAuction().getId(),
                e.getId(),
                e.getState(),
                e.getTransferConfirmedAt(),
                serverTime);
    }
}
