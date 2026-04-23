package com.slparcelauctions.backend.auction.mybids;

import java.time.OffsetDateTime;

/**
 * Single row on the bidder's My Bids dashboard. Combines the auction summary
 * ({@link AuctionSummaryForMyBids}), the caller's own bidding activity
 * (highest bid amount + timestamp, optional proxy max), and the derived
 * {@link MyBidStatus}.
 *
 * <p>{@code myProxyMaxAmount} is non-null only when the caller currently owns
 * an {@code ACTIVE} proxy on the auction. An {@code EXHAUSTED} or
 * {@code CANCELLED} proxy does not resurrect here — the dashboard renders the
 * effective state, not a history ledger.
 */
public record MyBidSummary(
        AuctionSummaryForMyBids auction,
        Long myHighestBidAmount,
        OffsetDateTime myHighestBidAt,
        Long myProxyMaxAmount,
        MyBidStatus myBidStatus) {}
