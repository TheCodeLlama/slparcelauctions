package com.slparcelauctions.backend.escrow.exception;

import lombok.Getter;

/**
 * Thrown when a caller requests escrow state for an auction that has no
 * escrow row — either the auction ended with {@code NO_BIDS} /
 * {@code RESERVE_NOT_MET} (no escrow is ever created), or the auction id
 * does not exist. Mapped to {@code 404 ESCROW_NOT_FOUND} by
 * {@link EscrowExceptionHandler}.
 */
@Getter
public class EscrowNotFoundException extends RuntimeException {

    private final Long auctionId;

    public EscrowNotFoundException(Long auctionId) {
        super("No escrow exists for auction " + auctionId);
        this.auctionId = auctionId;
    }
}
