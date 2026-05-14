/**
 * Decimal-to-percent string helpers for the agent-commission-rate UI.
 *
 * <p>Backend stores commission rates as PostgreSQL DECIMAL(5,4): the column
 * carries at most four fractional digits (0.0001 = 0.01%, 0.9999 = 99.99%).
 * Naively computing `rate * 100` then `.toString()` exposes IEEE-754 binary
 * float artifacts — the canonical example being `0.07 * 100 ===
 * 7.000000000000001` — which would surface as the literal `"7.000000000000001"`
 * in a controlled input's default value. These helpers do the conversion
 * with integer rounding so the user-facing string always matches the rate
 * the backend wrote.
 */

/**
 * Decimal commission rate (0..1) to a percentage string suitable as a
 * controlled input's default value. Trailing zeros are stripped so a round
 * 7% rate renders as `"7"`, not `"7.00"`.
 *
 * <p>Returns `""` for `null`, `undefined`, or any non-finite input — callers
 * use the empty string as the "no current rate" sentinel in the input.
 */
export function rateToPercentInput(rate: number | null | undefined): string {
  if (rate == null || !Number.isFinite(rate)) return "";
  // Round on integer-space: `rate * 10000` collapses the 4-decimal DB
  // precision onto an integer, and dividing by 100 brings it back to a
  // percent with at most 2 decimal places. Both operations dodge the binary
  // float artifacts that `rate * 100` produces directly.
  const percent = Math.round(rate * 10000) / 100;
  return percent.toString();
}

/**
 * Decimal commission rate (0..1) to a percentage string suitable for
 * read-only display ("Commission: 7.00%"). Always renders with exactly
 * two fractional digits to keep column widths stable across rows. Returns
 * `""` for `null` / `undefined` so callers can branch on the empty case.
 */
export function rateToPercentDisplay(rate: number | null | undefined): string {
  if (rate == null || !Number.isFinite(rate)) return "";
  return (Math.round(rate * 10000) / 100).toFixed(2);
}
