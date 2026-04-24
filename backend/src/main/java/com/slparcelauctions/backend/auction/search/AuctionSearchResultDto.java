package com.slparcelauctions.backend.auction.search;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;

/**
 * Single card on the browse page / featured rows / Curator Tray list.
 * Shape defined in Epic 07 sub-spec 1 §5.1.1.
 *
 * <p>Resolved server-side:
 * <ul>
 *   <li>{@code primaryPhotoUrl} — first AuctionPhoto by sort_order, or
 *       falls back to parcel.snapshotUrl if no seller photos.</li>
 *   <li>{@code reserveMet} — reservePrice IS NULL OR currentBid &gt;= reservePrice.</li>
 *   <li>{@code seller.averageRating}, {@code seller.reviewCount} — pulled
 *       from denormalized User columns.</li>
 * </ul>
 *
 * <p>{@code endOutcome} is null for ACTIVE auctions (which is all the
 * {@code /search} endpoint returns — it filters to status == ACTIVE). The
 * field is populated for ended auctions so the Curator Tray's
 * {@code ended_only} status_filter view can surface SOLD /
 * RESERVE_NOT_MET / NO_BIDS chips on the frontend without a second fetch
 * per card.
 *
 * <p>{@code distanceRegions} is null unless the request carried
 * near_region and the region resolved. Populated as a BigDecimal rounded
 * to 1 decimal place.
 */
public record AuctionSearchResultDto(
        Long id,
        String title,
        AuctionStatus status,
        AuctionEndOutcome endOutcome,
        ParcelSummaryDto parcel,
        String primaryPhotoUrl,
        SellerSummaryDto seller,
        VerificationTier verificationTier,
        Long currentBid,
        Long startingBid,
        Long reservePrice,
        boolean reserveMet,
        Long buyNowPrice,
        Integer bidCount,
        OffsetDateTime endsAt,
        boolean snipeProtect,
        Integer snipeWindowMin,
        BigDecimal distanceRegions) {
}
