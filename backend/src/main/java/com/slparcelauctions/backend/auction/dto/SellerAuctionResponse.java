package com.slparcelauctions.backend.auction.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;

public record SellerAuctionResponse(
        Long id,
        Long sellerId,
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
        Long winnerId,
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
        OffsetDateTime updatedAt) {
}
