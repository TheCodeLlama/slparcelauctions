package com.slparcelauctions.backend.auction;

/**
 * Full internal auction status enum. The four terminal "why-it-ended" states
 * (COMPLETED, CANCELLED, EXPIRED, DISPUTED) collapse to ENDED in
 * {@link com.slparcelauctions.backend.auction.dto.PublicAuctionStatus} when
 * serialized for non-sellers. See spec §6 for the collapse rules.
 */
public enum AuctionStatus {
    DRAFT,
    DRAFT_PAID,
    VERIFICATION_PENDING,
    VERIFICATION_FAILED,
    ACTIVE,
    ENDED,
    ESCROW_PENDING,
    ESCROW_FUNDED,
    TRANSFER_PENDING,
    COMPLETED,
    CANCELLED,
    EXPIRED,
    DISPUTED,
    // intentionally not in LOCKING_STATUSES — suspension releases the parcel for re-listing.
    SUSPENDED
}
