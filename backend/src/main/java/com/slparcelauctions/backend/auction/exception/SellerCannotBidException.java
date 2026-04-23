package com.slparcelauctions.backend.auction.exception;

/**
 * Raised by {@code BidService.placeBid} when the bidder is the seller of the
 * auction. Mapped to {@code 403 SELLER_CANNOT_BID} by
 * {@code AuctionExceptionHandler} — no retry helps, the caller is permanently
 * disallowed from bidding on this specific auction.
 */
public class SellerCannotBidException extends RuntimeException {

    public SellerCannotBidException() {
        super("Sellers cannot bid on their own auction.");
    }
}
