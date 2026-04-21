package com.slparcelauctions.backend.auction;

/**
 * How a {@link Bid} row entered the system.
 *
 * <ul>
 *   <li>{@code MANUAL} — placed directly by a bidder via the place-bid API.</li>
 *   <li>{@code PROXY_AUTO} — emitted by the proxy-bidding engine when an
 *       existing {@link ProxyBid} was re-priced above the current bid.</li>
 *   <li>{@code BUY_NOW} — terminal purchase that collapses the auction to
 *       {@link AuctionEndOutcome#BOUGHT_NOW}.</li>
 * </ul>
 */
public enum BidType {
    MANUAL,
    PROXY_AUTO,
    BUY_NOW
}
