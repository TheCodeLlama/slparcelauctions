package com.slparcelauctions.backend.auction.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

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
 */
public record PublicAuctionResponse(
        Long id,
        Long sellerId,
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
        EscrowState escrowState,
        OffsetDateTime transferConfirmedAt) {
}
