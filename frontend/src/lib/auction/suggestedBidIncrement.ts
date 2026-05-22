/**
 * Suggested minimum bid increment (L$) derived from the starting bid.
 * Mirrors the backend BidIncrementSuggester tiers. Used to pre-fill the
 * create form; the creator can override.
 *
 * Tier table:
 *   startingBid < 1000    -> L$50
 *   startingBid < 10000   -> L$100
 *   startingBid < 100000  -> L$500
 *   startingBid >= 100000 -> L$1000
 */
export function suggestedBidIncrement(startingBid: number): number {
  if (startingBid < 1_000) return 50;
  if (startingBid < 10_000) return 100;
  if (startingBid < 100_000) return 500;
  return 1_000;
}
