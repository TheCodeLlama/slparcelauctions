// frontend/src/lib/auth/redirects.ts

const FALLBACK = "/dashboard";

/**
 * Parses a `?next=` query parameter and returns a safe internal URL.
 *
 * Security rules:
 *   - Must start with / (relative path)
 *   - Must NOT start with // (protocol-relative URL — open redirect attack)
 *   - Must NOT contain newlines or control characters (header-injection adjacent)
 *   - Falls back to /dashboard on any failure
 *
 * The `//evil.example/phish` check is the load-bearing one. Without it, a
 * malicious link like `/login?next=//evil.example/phish` would bounce the user
 * to evil.example after a successful login. This pattern is the standard
 * open-redirect attack vector for web auth flows.
 *
 * See spec §8.
 */
export function getSafeRedirect(next: string | null | undefined): string {
  if (!next) return FALLBACK;
  if (typeof next !== "string") return FALLBACK;
  if (!next.startsWith("/")) return FALLBACK;
  if (next.startsWith("//")) return FALLBACK;
  if (/[\r\n\0]/.test(next)) return FALLBACK;
  return next;
}
