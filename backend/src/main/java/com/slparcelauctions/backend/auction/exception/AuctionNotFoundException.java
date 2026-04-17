package com.slparcelauctions.backend.auction.exception;

public class AuctionNotFoundException extends RuntimeException {

    private final Long auctionId;

    public AuctionNotFoundException(Long auctionId) {
        super("Auction not found: " + auctionId);
        this.auctionId = auctionId;
    }

    public Long getAuctionId() {
        return auctionId;
    }
}
