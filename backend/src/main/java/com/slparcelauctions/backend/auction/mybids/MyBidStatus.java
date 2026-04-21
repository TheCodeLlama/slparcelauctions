package com.slparcelauctions.backend.auction.mybids;

/**
 * Derived per-user bid status presented on the bidder dashboard. Not persisted:
 * the deriver computes it on the fly from the underlying {@code Auction} state
 * plus the caller's identity. See {@link MyBidStatusDeriver} for the exact
 * mapping and spec §10 for the design rationale.
 */
public enum MyBidStatus {
    /** Auction is ACTIVE and the caller holds the current high bid. */
    WINNING,
    /** Auction is ACTIVE and someone else holds the current high bid. */
    OUTBID,
    /** Auction ENDED+SOLD or ENDED+BOUGHT_NOW with the caller as winner. */
    WON,
    /** Auction ENDED but someone else won (or no meaningful "place" for the caller). */
    LOST,
    /** Auction ENDED without meeting reserve; the caller was the high bidder. */
    RESERVE_NOT_MET,
    /** Auction was CANCELLED by the seller with the caller already bidding. */
    CANCELLED,
    /** Auction was SUSPENDED due to ownership-check failure. */
    SUSPENDED
}
