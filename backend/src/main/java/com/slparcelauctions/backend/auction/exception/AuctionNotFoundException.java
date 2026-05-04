package com.slparcelauctions.backend.auction.exception;

import java.util.UUID;

public class AuctionNotFoundException extends RuntimeException {

    private final Long auctionId;

    public AuctionNotFoundException(Long auctionId) {
        super("Auction not found: " + auctionId);
        this.auctionId = auctionId;
    }

    public AuctionNotFoundException(UUID publicId) {
        super("Auction not found: " + publicId);
        this.auctionId = null;
    }

    public Long getAuctionId() {
        return auctionId;
    }
}
