/**
 * Tiny relative-time helper for UI labels like {@code "2m ago"} /
 * {@code "just now"} / {@code "3h ago"}. Used by
 * {@code BidHistoryRow} and {@code AuctionEndedPanel}.
 *
 * Kept deliberately small — we don't need full i18n here, just the
 * five buckets the spec surfaces (seconds, minutes, hours, days, weeks).
 * If richer formatting ever lands, swap the implementation to
 * {@code Intl.RelativeTimeFormat} without changing the signature.
 *
 * The function accepts either a Date or an ISO-8601 string; strings are
 * parsed with {@code Date.parse} which is good enough for backend-emitted
 * ISO timestamps (always Z-suffixed, milliseconds optional).
 *
 * {@code now} is injectable so tests can pin the reference instant
 * without mocking the global clock.
 */
export function formatRelativeTime(
  from: Date | string,
  now: Date | number = Date.now(),
): string {
  const nowMs = typeof now === "number" ? now : now.getTime();
  const fromDate = typeof from === "string" ? new Date(from) : from;
  const fromMs = fromDate.getTime();
  if (!Number.isFinite(fromMs)) return "";

  const diffMs = nowMs - fromMs;
  const diffSec = Math.round(diffMs / 1_000);

  if (diffSec < 10) return "just now";
  if (diffSec < 60) return `${diffSec}s ago`;
  const diffMin = Math.round(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.round(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  const diffDay = Math.round(diffHr / 24);
  if (diffDay < 7) return `${diffDay}d ago`;
  const diffWk = Math.round(diffDay / 7);
  if (diffWk < 5) return `${diffWk}w ago`;
  // Beyond ~5 weeks we fall back to an absolute date — the edge cases
  // for this UI are all recent, and a longer relative string ("8w ago")
  // is less useful than "Apr 20, 2025".
  return fromDate.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

/**
 * Formats a date as an absolute tooltip string, suitable for the
 * {@code title} attribute on a relative timestamp. Uses the visitor's
 * locale; the ISO fallback preserves tzinfo when Intl is unavailable
 * (older Node without full-icu, mostly).
 */
export function formatAbsoluteTime(from: Date | string): string {
  const fromDate = typeof from === "string" ? new Date(from) : from;
  if (!Number.isFinite(fromDate.getTime())) return "";
  try {
    return fromDate.toLocaleString(undefined, {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit",
    });
  } catch {
    return fromDate.toISOString();
  }
}
