package com.slparcelauctions.backend.auction.dto;

import java.util.Set;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * All fields optional. Omitted fields leave the auction unchanged.
 * parcelId is intentionally not editable — use a fresh draft instead.
 *
 * <p>Per sub-spec 2 §7.1/§7.2, {@code verificationMethod} is intentionally
 * omitted here — it is chosen by the seller on PUT /auctions/{id}/verify
 * (see {@code AuctionVerifyRequest}), which is the sole place the group-land
 * gate is enforced. Accepting it via the general update endpoint would let
 * a client bypass that gate.
 */
public record AuctionUpdateRequest(
        @Min(1) Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        Integer durationHours,
        Boolean snipeProtect,
        Integer snipeWindowMin,
        @Size(max = 5000) String sellerDesc,
        @Size(max = 10) Set<String> tags) {
}
