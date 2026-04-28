package com.slparcelauctions.backend.user.deletion.exception;

import java.util.List;

public class ActiveAuctionsException extends RuntimeException implements DeletionPreconditionException {
    private final List<Long> auctionIds;

    public ActiveAuctionsException(List<Long> auctionIds) {
        super("User has " + auctionIds.size() + " active auction(s)");
        this.auctionIds = auctionIds;
    }

    @Override public String getCode() { return "ACTIVE_AUCTIONS"; }
    @Override public List<Long> getBlockingIds() { return auctionIds; }
}
