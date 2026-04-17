package com.slparcelauctions.backend.auction.dto;

import java.util.Set;

import com.slparcelauctions.backend.auction.VerificationMethod;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * All fields optional. Omitted fields leave the auction unchanged.
 * parcelId is intentionally not editable — use a fresh draft instead.
 */
public record AuctionUpdateRequest(
        VerificationMethod verificationMethod,
        @Min(1) Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        Integer durationHours,
        Boolean snipeProtect,
        Integer snipeWindowMin,
        @Size(max = 5000) String sellerDesc,
        @Size(max = 10) Set<String> tags) {
}
