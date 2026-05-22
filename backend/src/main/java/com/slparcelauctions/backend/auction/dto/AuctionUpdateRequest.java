package com.slparcelauctions.backend.auction.dto;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * All fields optional. Omitted fields leave the auction unchanged.
 *
 * <p>When {@code slParcelUuid} is present and differs from the auction's
 * current parcel UUID, {@code AuctionService.update} performs a fresh
 * lookup and refreshes the parcel snapshot in place.
 *
 * <p>Verification metadata is not accepted here -- the ownership-only
 * verification flow is triggered by {@code PUT /auctions/{publicId}/verify}
 * with an empty body and reads owner UUIDs straight from the World API.
 */
public record AuctionUpdateRequest(
        UUID slParcelUuid,
        @Size(max = 120, message = "title must be at most 120 characters")
        String title,
        @Min(1) Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        @Min(value = 1, message = "bidIncrement must be at least 1")
        Long bidIncrement,
        Integer durationHours,
        Boolean snipeProtect,
        Integer snipeWindowMin,
        @Size(max = 5000) String sellerDesc,
        @Size(max = 10) Set<String> tags) {
}
