/**
 * Frontend mirror of the backend {@code BidIncrementTable} (see
 * backend/src/main/java/com/slparcelauctions/backend/auction/BidIncrementTable.java
 * — authoritative source, this module MUST mirror its tiers exactly).
 *
 * Tiers per DESIGN.md §4.7:
 * <ul>
 *   <li>currentBid &lt; 1000        → L$50</li>
 *   <li>currentBid &lt; 10000       → L$100</li>
 *   <li>currentBid &lt; 100000      → L$500</li>
 *   <li>currentBid &gt;= 100000     → L$1000</li>
 * </ul>
 *
 * The increment is read from the CURRENT bid, not the proposed amount —
 * this keeps the step-up monotonically non-decreasing and prevents the
 * off-by-one where the UI shrinks the increment after crossing a tier.
 *
 * Used client-side only to preview the minimum-required next bid in the
 * place-bid form; the server remains the single source of truth on the
 * commit path (BidService enforces the same table under the row lock).
 */
export function minIncrement(currentBid: number): number {
  if (currentBid < 1_000) return 50;
  if (currentBid < 10_000) return 100;
  if (currentBid < 100_000) return 500;
  return 1_000;
}

/**
 * Computes the minimum L$ amount a next bid must satisfy.
 *
 * When the auction has no bids yet ({@code currentHighBid == null || 0}),
 * the floor is {@code startingBid}. Otherwise it is {@code currentHighBid
 * + minIncrement(currentHighBid)}.
 *
 * {@code currentHighBid} may arrive as a BigDecimal-string from the
 * backend — the helper coerces it once so callers can pass the raw DTO
 * field through without type gymnastics.
 */
export function minRequiredBid(
  currentHighBid: number | string | null,
  startingBid: number,
): number {
  const current =
    currentHighBid == null
      ? 0
      : typeof currentHighBid === "string"
      ? Number(currentHighBid)
      : currentHighBid;
  if (!Number.isFinite(current) || current <= 0) return startingBid;
  return current + minIncrement(current);
}
