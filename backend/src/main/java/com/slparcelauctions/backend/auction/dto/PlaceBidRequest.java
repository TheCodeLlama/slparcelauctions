package com.slparcelauctions.backend.auction.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/auctions/{id}/bids}. The
 * authenticated principal supplies the bidder identity — the request only
 * carries the proposed amount. Rejected by {@code @Valid} before reaching
 * {@code BidService} when the body is missing, zero, or negative (400).
 * Semantic "is this amount above the increment floor" validation is
 * enforced by {@code BidService.placeBid} under the pessimistic lock so
 * the check runs against the freshest {@code currentBid}.
 */
public record PlaceBidRequest(
        @NotNull @Min(1) Long amount) {
}
