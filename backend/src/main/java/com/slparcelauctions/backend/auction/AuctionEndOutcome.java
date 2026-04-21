package com.slparcelauctions.backend.auction;

/**
 * Terminal resolution of an auction, stored on
 * {@code Auction.endOutcome} when the auction transitions out of
 * {@link AuctionStatus#ACTIVE}.
 *
 * <ul>
 *   <li>{@code SOLD} — highest bid met or exceeded the reserve price.</li>
 *   <li>{@code RESERVE_NOT_MET} — bids exist but none met the reserve.</li>
 *   <li>{@code NO_BIDS} — auction expired without a single bid.</li>
 *   <li>{@code BOUGHT_NOW} — ended early via buy-it-now.</li>
 * </ul>
 */
public enum AuctionEndOutcome {
    SOLD,
    RESERVE_NOT_MET,
    NO_BIDS,
    BOUGHT_NOW
}
