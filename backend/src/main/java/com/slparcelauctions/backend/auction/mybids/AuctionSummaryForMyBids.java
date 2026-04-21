package com.slparcelauctions.backend.auction.mybids;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionStatus;

/**
 * Compact auction projection used by the My Bids dashboard. Keeps the payload
 * small — enough to render a card with parcel details, status, timing, and
 * current/final price — without pulling the full seller auction response.
 * Parcel "name" is sourced from {@code parcel.location} (the human-readable
 * label); seller display name from {@code auction.seller.displayName}.
 */
public record AuctionSummaryForMyBids(
        Long id,
        AuctionStatus status,
        AuctionEndOutcome endOutcome,
        String parcelName,
        String parcelRegion,
        Integer parcelAreaSqm,
        String snapshotUrl,
        OffsetDateTime endsAt,
        OffsetDateTime endedAt,
        Long currentBid,
        Long bidderCount,
        Long sellerUserId,
        String sellerDisplayName) {

    /**
     * Builds a summary from a live {@code Auction} aggregate. The caller must
     * ensure {@code auction.parcel} and {@code auction.seller} are initialized
     * (use {@code @EntityGraph} or access them inside the same transaction).
     * The "bidder count" field is sourced from {@code auction.bidCount}; it
     * counts bid rows rather than distinct bidders — a small semantic blur
     * acknowledged by spec §9 that is acceptable at Phase 1 volumes.
     */
    public static AuctionSummaryForMyBids from(Auction a) {
        Integer bidCount = a.getBidCount();
        return new AuctionSummaryForMyBids(
                a.getId(),
                a.getStatus(),
                a.getEndOutcome(),
                a.getParcel() == null ? null : a.getParcel().getLocation(),
                a.getParcel() == null ? null : a.getParcel().getRegionName(),
                a.getParcel() == null ? null : a.getParcel().getAreaSqm(),
                a.getParcel() == null ? null : a.getParcel().getSnapshotUrl(),
                a.getEndsAt(),
                a.getEndedAt(),
                a.getCurrentBid(),
                bidCount == null ? 0L : bidCount.longValue(),
                a.getSeller() == null ? null : a.getSeller().getId(),
                a.getSeller() == null ? null : a.getSeller().getDisplayName());
    }
}
