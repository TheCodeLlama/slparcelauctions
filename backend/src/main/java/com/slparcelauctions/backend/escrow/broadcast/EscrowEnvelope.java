package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;

/**
 * Sealed hierarchy of escrow broadcast envelopes published on
 * {@code /topic/auction/{id}}. Each variant has a fixed {@code type}
 * discriminator string; clients route on {@code type}. Spec §8.
 *
 * <p>The {@code permits} clause widens per task as new escrow transition
 * variants land. Task 2 shipped {@link EscrowCreatedEnvelope}; Task 3
 * added {@link EscrowDisputedEnvelope}; Task 5 adds
 * {@link EscrowFundedEnvelope}.
 */
public sealed interface EscrowEnvelope
        permits EscrowCreatedEnvelope, EscrowDisputedEnvelope, EscrowFundedEnvelope {

    String type();
    Long auctionId();
    Long escrowId();
    OffsetDateTime serverTime();
}
