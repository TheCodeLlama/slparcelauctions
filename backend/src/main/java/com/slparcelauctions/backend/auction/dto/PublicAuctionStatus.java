package com.slparcelauctions.backend.auction.dto;

/**
 * Reduced status enum for the public view. The four terminal statuses
 * (COMPLETED, CANCELLED, EXPIRED, DISPUTED) collapse to ENDED here — the
 * public must not be able to infer why an auction ended. Enforced at the
 * type level: AuctionDtoMapper.toPublicStatus exhaustively maps from the
 * internal enum, and PublicAuctionResponse.status is typed to this enum,
 * so a serialization bug cannot leak a terminal status.
 */
public enum PublicAuctionStatus {
    ACTIVE,
    ENDED
}
