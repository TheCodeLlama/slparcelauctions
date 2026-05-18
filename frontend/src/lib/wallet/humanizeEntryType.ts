/**
 * Turn an UPPER_SNAKE_CASE ledger entry type into a human-readable label.
 *
 * Used as the runtime fallback in both the personal and group wallet ledger
 * renderers: when the backend ships a new entry type before the frontend
 * union catches up, the renderer must still produce a sensible label instead
 * of crashing the whole wallet (see the deploy-skew white-screen regression).
 *
 * Example: `"ADMIN_ADJUSTMENT"` -> `"Admin adjustment"`.
 *
 * Rule: split on `_`, lowercase every word, capitalise the first word only.
 * Empty / whitespace-only input degrades to a generic "Transaction" so the
 * destructure sites never receive an empty string.
 */
export function humanizeEntryType(raw: string): string {
  const words = String(raw ?? "")
    .trim()
    .split(/_+/)
    .filter((w) => w.length > 0)
    .map((w) => w.toLowerCase());

  if (words.length === 0) return "Transaction";

  words[0] = words[0].charAt(0).toUpperCase() + words[0].slice(1);
  return words.join(" ");
}
