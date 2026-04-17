package com.slparcelauctions.backend.auction.fraud;

/**
 * Reasons a fraud flag can be raised during ownership monitoring. See spec §8.2
 * (ownership-monitoring outcomes) and §8.3 (state transitions).
 */
public enum FraudFlagReason {
    OWNERSHIP_CHANGED_TO_UNKNOWN,
    PARCEL_DELETED_OR_MERGED,
    WORLD_API_FAILURE_THRESHOLD
}
