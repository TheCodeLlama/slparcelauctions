package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.ProxyBid;
import com.slparcelauctions.backend.auction.ProxyBidStatus;

/**
 * Response body for the four proxy-bid endpoints. Carries the bare proxy row
 * state so the client can render the current situation ("You have an ACTIVE
 * proxy at L$X / you were exhausted at L$Y / you cancelled at Z"). Nothing
 * auction-scoped is included because the client already holds the auction
 * context — the bid-settlement envelope broadcast on WS covers any
 * currentBid/endsAt changes the proxy call may have caused.
 */
public record ProxyBidResponse(
        Long proxyBidId,
        Long auctionId,
        Long maxAmount,
        ProxyBidStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ProxyBidResponse from(ProxyBid proxy) {
        return new ProxyBidResponse(
                proxy.getId(),
                proxy.getAuction().getId(),
                proxy.getMaxAmount(),
                proxy.getStatus(),
                proxy.getCreatedAt(),
                proxy.getUpdatedAt());
    }
}
