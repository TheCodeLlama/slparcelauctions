package com.slparcelauctions.backend.auction.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;

/**
 * Seller-only auction view. Extends the public view with reserve price,
 * listing-fee + commission amounts, verification metadata, and the internal
 * {@link AuctionStatus} (pre-ACTIVE drafts + SUSPENDED visible to the seller,
 * collapsed to ENDED in the public view).
 *
 * <p>{@code escrowState} and {@code transferConfirmedAt} mirror the escrow row
 * (if any) so the seller's listing detail + dashboard can render the same
 * EscrowChip primitive as the public view; both are {@code null} for
 * pre-ENDED auctions.
 */
public record SellerAuctionResponse(
        UUID publicId,
        UUID sellerPublicId,
        String title,
        ParcelResponse parcel,
        AuctionStatus status,
        VerificationMethod verificationMethod,
        VerificationTier verificationTier,
        PendingVerification pendingVerification,
        String verificationNotes,
        Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        Long currentBid,
        Integer bidCount,
        BigDecimal currentHighBid,
        Long bidderCount,
        UUID winnerPublicId,
        Integer durationHours,
        Boolean snipeProtect,
        Integer snipeWindowMin,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime originalEndsAt,
        String sellerDesc,
        List<ParcelTagResponse> tags,
        List<AuctionPhotoResponse> photos,
        Boolean listingFeePaid,
        Long listingFeeAmt,
        String listingFeeTxn,
        OffsetDateTime listingFeePaidAt,
        BigDecimal commissionRate,
        Long commissionAmt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        EscrowState escrowState,
        OffsetDateTime transferConfirmedAt) {
}
