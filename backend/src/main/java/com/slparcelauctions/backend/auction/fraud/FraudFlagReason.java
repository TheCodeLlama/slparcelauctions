package com.slparcelauctions.backend.auction.fraud;

/**
 * Reasons a fraud flag can be raised during ownership monitoring. See spec §8.2
 * (ownership-monitoring outcomes) and §8.3 (state transitions).
 */
public enum FraudFlagReason {
    OWNERSHIP_CHANGED_TO_UNKNOWN,
    PARCEL_DELETED_OR_MERGED,
    WORLD_API_FAILURE_THRESHOLD,
    /**
     * Raised when an escrow payment arrives from an avatar UUID that does not
     * match the auction winner's registered {@code sl_avatar_uuid}. The
     * terminal refunds the L$, the escrow stays in {@code ESCROW_PENDING}, and
     * the flag gives the Epic 10 admin dashboard a review handle. See spec
     * §4 / §13.2.
     */
    ESCROW_WRONG_PAYER
}
