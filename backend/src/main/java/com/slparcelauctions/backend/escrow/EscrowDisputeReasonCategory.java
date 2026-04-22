package com.slparcelauctions.backend.escrow;

/**
 * Categorical reason a seller or winner files an escrow dispute. Stored on
 * {@code escrows.dispute_reason_category} as a string (not enum FK) so the
 * history is stable even if future categories are added or deprecated.
 * Spec §4.4 / §8.
 */
public enum EscrowDisputeReasonCategory {
    SELLER_NOT_RESPONSIVE,
    WRONG_PARCEL_TRANSFERRED,
    PAYMENT_NOT_CREDITED,
    FRAUD_SUSPECTED,
    OTHER
}
