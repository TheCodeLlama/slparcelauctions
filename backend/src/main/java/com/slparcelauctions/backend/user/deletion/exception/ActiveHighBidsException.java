package com.slparcelauctions.backend.user.deletion.exception;

import java.util.List;

public class ActiveHighBidsException extends RuntimeException implements DeletionPreconditionException {
    private final List<Long> auctionIds;

    public ActiveHighBidsException(List<Long> auctionIds) {
        super("User is the current high bidder on " + auctionIds.size() + " active auction(s)");
        this.auctionIds = auctionIds;
    }

    @Override public String getCode() { return "ACTIVE_HIGH_BIDS"; }
    @Override public List<Long> getBlockingIds() { return auctionIds; }
}
