package com.slparcelauctions.backend.auction;

/**
 * Create-time suggestion source for an auction's minimum bid increment.
 *
 * <p>This is a SUGGESTION ONLY. It pre-fills the create form and is the
 * fallback when a create request omits an increment. It is NOT a runtime
 * bid-validation rule - once an auction exists, its {@code bid_increment}
 * column is the sole authority (see BidService / ProxyBidService).
 *
 * <p>The tier breakpoints are unchanged from the former BidIncrementTable
 * (DESIGN.md section 4.7); only the framing and the input changed - the
 * suggestion keys off the auction's starting bid, since at create time
 * there is no current bid.
 */
public final class BidIncrementSuggester {

    private BidIncrementSuggester() {
        // no instances
    }

    /**
     * Suggested minimum bid increment in L$, derived from the starting bid.
     * Always strictly positive.
     */
    public static long suggestedIncrement(long startingBid) {
        if (startingBid < 1_000L)    return 50L;
        if (startingBid < 10_000L)   return 100L;
        if (startingBid < 100_000L)  return 500L;
        return 1_000L;
    }
}
