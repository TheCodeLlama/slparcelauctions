package com.slparcelauctions.backend.auction.dto;

import java.util.Set;

import com.slparcelauctions.backend.auction.VerificationMethod;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/auctions.
 * Validation rules enforced via JSR-380 + extra checks in AuctionService
 * (reserve &gt;= starting, buy_now &gt;= max, etc.).
 */
public record AuctionCreateRequest(
        @NotNull Long parcelId,
        @NotNull VerificationMethod verificationMethod,
        @NotNull @Min(1) Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        @NotNull Integer durationHours,          // validated to be in {24,48,72,168,336}
        @NotNull Boolean snipeProtect,
        Integer snipeWindowMin,                    // required iff snipeProtect
        @Size(max = 5000) String sellerDesc,
        @Size(max = 10) Set<String> tags) {       // parcel_tag codes
}
