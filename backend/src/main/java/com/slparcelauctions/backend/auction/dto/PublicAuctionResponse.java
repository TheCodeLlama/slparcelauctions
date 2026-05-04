package com.slparcelauctions.backend.auction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;

/**
 * Public view of an auction. Notably excludes: winner_id, reservePrice (exposes only
 * hasReserve + reserveMet), listing fee fields, verification_notes, commission fields,
 * agent fee fields, assigned_bot_uuid, sale_sentinel_price, last_bot_check_at,
 * bot_check_failures, pendingVerification, seller's internal verification_method.
 *
 * <p>{@code escrowState} and {@code transferConfirmedAt} are populated when the
 * auction has a corresponding escrow row (post-ENDED lifecycles); both are
 * {@code null} for ACTIVE auctions. The public view intentionally surfaces
 * escrow presence and (for COMPLETED sales) the transfer-confirmation
 * timestamp — information that is already observable via parcel-ownership —
 * without leaking amounts or dispute details.
 *
 * <p>{@code sellerId} is preserved as a top-level field for backwards
 * compatibility with existing consumers; the richer {@link SellerSummary}
 * block adds reputation + tenure signals (averageRating, reviewCount,
 * completedSales, completionRate, memberSince) for the listing-detail
 * page's seller card. {@code completionRate} is server-computed by
 * {@code SellerCompletionRateMapper} so the private {@code cancelledWithBids}
 * and {@code escrowExpiredUnfulfilled} counters never appear in the response.
 */
public record PublicAuctionResponse(
        UUID publicId,
        UUID sellerPublicId,
        String title,
        ParcelResponse parcel,
        PublicAuctionStatus status,
        VerificationTier verificationTier,
        Long startingBid,
        Boolean hasReserve,
        Boolean reserveMet,
        Long buyNowPrice,
        Long currentBid,
        Integer bidCount,
        BigDecimal currentHighBid,
        Long bidderCount,
        Integer durationHours,
        Boolean snipeProtect,
        Integer snipeWindowMin,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime originalEndsAt,
        String sellerDesc,
        List<ParcelTagResponse> tags,
        List<AuctionPhotoResponse> photos,
        SellerSummary seller,
        EscrowState escrowState,
        OffsetDateTime transferConfirmedAt) {

    /**
     * Enriched seller card for the listing-detail page. {@code averageRating}
     * and {@code completionRate} are nullable for new sellers; the UI renders
     * "—" rather than a misleading zero. {@code completionRate} is the
     * server-computed ratio of {@code completedSales / (completedSales +
     * cancelledWithBids + escrowExpiredUnfulfilled)}; the two denominator-
     * only counters are intentionally absent from this DTO.
     */
    public record SellerSummary(
            UUID publicId,
            String displayName,
            String avatarUrl,
            BigDecimal averageRating,
            Integer reviewCount,
            Integer completedSales,
            BigDecimal completionRate,
            LocalDate memberSince) {}
}
