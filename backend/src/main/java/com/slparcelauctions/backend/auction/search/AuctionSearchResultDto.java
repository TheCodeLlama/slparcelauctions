package com.slparcelauctions.backend.auction.search;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
 * <p>{@code distanceRegions} is null unless the request carried
 * near_region and the region resolved. Populated as a BigDecimal rounded
 * to 1 decimal place.
 */
public record AuctionSearchResultDto(
        Long id,
        String title,
        AuctionStatus status,
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
