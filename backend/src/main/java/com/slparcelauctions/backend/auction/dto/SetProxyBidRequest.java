package com.slparcelauctions.backend.auction.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/auctions/{id}/proxy-bid} (createProxy)
 * and {@code PUT /api/v1/auctions/{id}/proxy-bid} (updateProxyMax). The
 * authenticated principal supplies the bidder identity — only the max amount
 * is carried on the wire.
 *
 * <p>JSR-380 validation rejects null / non-positive values with 400 before
 * reaching {@code ProxyBidService}. Semantic validation (minimum-increment
 * floor, increase-only-on-exhausted, etc.) runs inside the service under the
 * pessimistic lock so checks race against the freshest {@code currentBid}.
 */
public record SetProxyBidRequest(
        @NotNull @Min(1) Long maxAmount) {
}
