package com.slparcelauctions.backend.auction.exception;

/**
 * Raised by {@code BidService.placeBid} when the proposed amount is strictly
 * below the required minimum — either the auction's {@code startingBid} (for
 * the first bid) or {@code currentBid + minIncrement(currentBid)} (for every
 * subsequent bid). Mapped to {@code 400 BID_TOO_LOW} by
 * {@code AuctionExceptionHandler}. The bidder can correct and resubmit, so
 * this is a 400 (bad input), not a 409 (state conflict).
 *
 * <p>Carries the structured {@code minRequired} so the handler can surface it
 * to the client for UI helpers (e.g. "Minimum bid is L$550").
 */
public class BidTooLowException extends RuntimeException {

    private final long minRequired;

    public BidTooLowException(long minRequired) {
        super("Minimum bid is L$" + minRequired + ".");
        this.minRequired = minRequired;
    }

    public long getMinRequired() {
        return minRequired;
    }
}
