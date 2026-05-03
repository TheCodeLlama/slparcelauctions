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
 * <p>Per sub-spec 2 §7.1/§7.2, {@code verificationMethod} is intentionally
 * omitted here — it is chosen by the seller on PUT /auctions/{id}/verify
 * (see {@code AuctionVerifyRequest}), which is the sole place the group-land
 * gate is enforced. Accepting it via the general update endpoint would let
 * a client bypass that gate.
 */
public record AuctionUpdateRequest(
        UUID slParcelUuid,
        @Size(max = 120, message = "title must be at most 120 characters")
        String title,
        @Min(1) Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        Integer durationHours,
        Boolean snipeProtect,
        Integer snipeWindowMin,
        @Size(max = 5000) String sellerDesc,
        @Size(max = 10) Set<String> tags) {
}
