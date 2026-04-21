package com.slparcelauctions.backend.auction;

/**
 * Static helper encoding the minimum-bid-increment tiers from DESIGN.md §4.7.
 *
 * <table>
 *   <caption>Increment tiers</caption>
 *   <tr><th>{@code currentBid} range</th><th>{@code minIncrement}</th></tr>
 *   <tr><td>L$0 - L$999</td>    <td>L$50</td></tr>
 *   <tr><td>L$1 000 - L$9 999</td>  <td>L$100</td></tr>
 *   <tr><td>L$10 000 - L$99 999</td><td>L$500</td></tr>
 *   <tr><td>L$100 000+</td>     <td>L$1 000</td></tr>
 * </table>
 *
 * <p>Consumers (bid-placement, proxy resolution) always read the tier from
 * the current bid, never from the proposed amount — this keeps the increment
 * monotonically non-decreasing as bids climb and prevents an off-by-one
 * that would shrink the increment right after crossing a tier boundary.
 *
 * <p>Tier-boundary behaviour is pinned by
 * {@code BidIncrementTableTest} — every boundary in the table is asserted
 * explicitly so a future refactor can't silently slide a threshold.
 */
public final class BidIncrementTable {

    private BidIncrementTable() {
        // no instances
    }

    /**
     * Returns the minimum step-up the next bid must satisfy, given the current
     * top bid on the auction. Always returns a strictly-positive L$ value.
     *
     * @param currentBid the auction's current top bid in L$ (0 when no bids yet)
     * @return the minimum L$ increment required for the next bid
     */
    public static long minIncrement(long currentBid) {
        if (currentBid < 1_000L)    return 50L;
        if (currentBid < 10_000L)   return 100L;
        if (currentBid < 100_000L)  return 500L;
        return 1_000L;
    }
}
