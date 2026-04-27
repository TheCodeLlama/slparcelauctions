package com.slparcelauctions.backend.admin.exception;

import com.slparcelauctions.backend.auction.AuctionStatus;
import lombok.Getter;

@Getter
public class AuctionNotSuspendedException extends RuntimeException {
    private final AuctionStatus currentStatus;

    public AuctionNotSuspendedException(AuctionStatus currentStatus) {
        super("Auction is currently " + (currentStatus == null ? "null" : currentStatus.name())
              + ", cannot be reinstated");
        this.currentStatus = currentStatus;
    }
}
