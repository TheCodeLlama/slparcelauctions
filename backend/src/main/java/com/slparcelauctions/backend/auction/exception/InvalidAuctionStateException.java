package com.slparcelauctions.backend.auction.exception;

import com.slparcelauctions.backend.auction.AuctionStatus;

public class InvalidAuctionStateException extends RuntimeException {

    private final Long auctionId;
    private final AuctionStatus currentState;
    private final String attemptedAction;

    public InvalidAuctionStateException(Long auctionId, AuctionStatus currentState, String attemptedAction) {
        super("Cannot '" + attemptedAction + "' auction " + auctionId + " in state " + currentState);
        this.auctionId = auctionId;
        this.currentState = currentState;
        this.attemptedAction = attemptedAction;
    }

    public Long getAuctionId() {
        return auctionId;
    }

    public AuctionStatus getCurrentState() {
        return currentState;
    }

    public String getAttemptedAction() {
        return attemptedAction;
    }
}
