package com.slparcelauctions.backend.promotion.exception;

import java.util.UUID;

/**
 * The auction already has an active PROMO-01 slot -- a duplicate purchase
 * attempt. Maps to HTTP 409 / problem code {@code PROMOTION_ALREADY_ACTIVE}.
 */
public class PromotionAlreadyActiveException extends RuntimeException {
    private final UUID auctionPublicId;

    public PromotionAlreadyActiveException(UUID auctionPublicId) {
        super("Auction " + auctionPublicId + " already has an active PROMO-01 slot");
        this.auctionPublicId = auctionPublicId;
    }

    public UUID getAuctionPublicId() {
        return auctionPublicId;
    }
}
