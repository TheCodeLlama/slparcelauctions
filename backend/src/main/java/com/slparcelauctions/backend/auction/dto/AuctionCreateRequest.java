package com.slparcelauctions.backend.auction.dto;

import java.util.Set;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/auctions.
 *
 * <p>Per Epic 03 sub-spec 2 §7.1, {@code verificationMethod} is no longer
 * part of auction creation. It is chosen by the seller at activate time
 * and sent on {@code PUT /api/v1/auctions/{id}/verify} via
 * {@link AuctionVerifyRequest}. {@code Auction.verificationMethod} stays
 * null between create and the verify trigger.
 *
 * <p>Validation rules enforced via JSR-380 + extra checks in AuctionService
 * (reserve &gt;= starting, buy_now &gt;= max, etc.).
 */
public record AuctionCreateRequest(
        @NotNull Long parcelId,
        @NotBlank(message = "title must not be blank")
        @Size(max = 120, message = "title must be at most 120 characters")
        String title,
        @NotNull @Min(1) Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        @NotNull Integer durationHours,          // validated to be in {24,48,72,168,336}
        @NotNull Boolean snipeProtect,
        Integer snipeWindowMin,                    // required iff snipeProtect
        @Size(max = 5000) String sellerDesc,
        @Size(max = 10) Set<String> tags) {       // parcel_tag codes
}
