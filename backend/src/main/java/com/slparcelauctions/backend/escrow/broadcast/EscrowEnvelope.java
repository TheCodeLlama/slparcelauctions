package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Sealed hierarchy of escrow broadcast envelopes published on
 * {@code /topic/auction/{id}}. Each variant has a fixed {@code type}
 * discriminator string; clients route on {@code type}. Spec §8.
 *
 * <p>The {@code permits} clause widens per task as new escrow transition
 * variants land. Task 2 shipped {@link EscrowCreatedEnvelope}; Task 3
 * added {@link EscrowDisputedEnvelope}; Task 5 added
 * {@link EscrowFundedEnvelope}; Task 6 added
 * {@link EscrowTransferConfirmedEnvelope} and {@link EscrowFrozenEnvelope}
 * for the ownership-monitor outcomes; Task 7 added
 * {@link EscrowCompletedEnvelope}, {@link EscrowRefundCompletedEnvelope},
 * and {@link EscrowPayoutStalledEnvelope} for the terminal command
 * dispatcher / callback pipeline. Task 8 adds
 * {@link EscrowExpiredEnvelope} for the scheduled timeout sweeper.
 */
public sealed interface EscrowEnvelope
        permits EscrowCreatedEnvelope,
                EscrowDisputedEnvelope,
                EscrowFundedEnvelope,
                EscrowTransferConfirmedEnvelope,
                EscrowFrozenEnvelope,
                EscrowCompletedEnvelope,
                EscrowRefundCompletedEnvelope,
                EscrowPayoutStalledEnvelope,
                EscrowExpiredEnvelope {

    String type();
    UUID auctionPublicId();
    UUID escrowPublicId();
    OffsetDateTime serverTime();
}
