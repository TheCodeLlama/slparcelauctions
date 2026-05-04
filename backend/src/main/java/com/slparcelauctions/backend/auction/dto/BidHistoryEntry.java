package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.Bid;
import com.slparcelauctions.backend.auction.BidType;

/**
 * Publicly visible bid-history row returned by
 * {@code GET /api/v1/auctions/{id}/bids}. Deliberately omits {@code ipAddress}
 * (abuse-audit only) and {@code proxyBidId} (internal linkage) — bid history
 * is public per DESIGN.md §1589-1591 but identity + position climbs only,
 * never sensitive source metadata.
 *
 * <p>{@code bidType} IS included so the UI can distinguish {@code MANUAL}
 * from {@code PROXY_AUTO} climbs, matching eBay's visible-proxy convention.
 */
public record BidHistoryEntry(
        UUID bidPublicId,
        UUID userPublicId,
        String bidderDisplayName,
        Long amount,
        BidType bidType,
        Integer snipeExtensionMinutes,
        OffsetDateTime newEndsAt,
        OffsetDateTime createdAt) {

    public static BidHistoryEntry from(Bid bid) {
        return new BidHistoryEntry(
                bid.getPublicId(),
                bid.getBidder().getPublicId(),
                bid.getBidder().getDisplayName(),
                bid.getAmount(),
                bid.getBidType(),
                bid.getSnipeExtensionMinutes(),
                bid.getNewEndsAt(),
                bid.getCreatedAt());
    }
}
