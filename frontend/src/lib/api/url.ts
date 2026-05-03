/**
 * Resolves an opaque-path URL emitted by the backend (e.g.
 * "/api/v1/photos/3") against the configured API base. Absolute
 * http(s) URLs pass through unchanged. Returns null for null/undefined
 * input so callers can render a no-op placeholder without branching.
 *
 * NOTE: NEXT_PUBLIC_API_URL is read lazily (inside the function) so that
 * Vitest's vi.stubEnv() can override it in tests. In Next.js production
 * builds the value is inlined at bundle time, so the runtime lookup is a
 * no-op — but the test environment needs the late binding.
 */
export function apiUrl(path: string | null | undefined): string | null {
  if (path == null) return null;
  if (/^https?:\/\//i.test(path)) return path;
  const base = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
  return `${base}${path}`;
}
