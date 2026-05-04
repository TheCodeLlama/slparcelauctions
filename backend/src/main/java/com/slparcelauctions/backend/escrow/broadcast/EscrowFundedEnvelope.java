package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;

/**
 * WebSocket envelope broadcast when an escrow transitions from
 * {@link EscrowState#ESCROW_PENDING} through the transient {@code FUNDED}
 * landing to {@link EscrowState#TRANSFER_PENDING} on a valid terminal
 * payment. Published after commit by {@code EscrowService.acceptPayment} so
 * subscribers never observe a row that gets rolled back with the payment
 * transaction.
 *
 * <p>The {@code state} on the wire is the post-atomic terminal state
 * {@code TRANSFER_PENDING} — the intermediate {@code FUNDED} is never
 * externally observable by design (spec §4). Clients infer "payment was
 * received" from the {@code type} discriminator and the presence of
 * {@code transferDeadline}. See spec §8.
 */
public record EscrowFundedEnvelope(
        String type,
        UUID auctionPublicId,
        UUID escrowPublicId,
        EscrowState state,
        OffsetDateTime transferDeadline,
        OffsetDateTime serverTime) implements EscrowEnvelope {

    public static EscrowFundedEnvelope of(Escrow e, OffsetDateTime serverTime) {
        return new EscrowFundedEnvelope(
                "ESCROW_FUNDED",
                e.getAuction().getPublicId(),
                e.getPublicId(),
                e.getState(),
                e.getTransferDeadline(),
                serverTime);
    }
}
