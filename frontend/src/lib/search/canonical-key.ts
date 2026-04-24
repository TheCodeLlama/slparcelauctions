import type { AuctionSearchQuery } from "@/types/search";

/**
 * Stable, deterministic JSON hash for React Query's queryKey. Two queries
 * that represent the same logical filter set (array order or key order
 * differs) must produce the same key, else useQuery remounts unnecessarily
 * and discards cached data.
 *
 * Rules:
 *   - Drop null / undefined fields entirely (they are equivalent to absent).
 *   - Sort array values so ["A","B"] and ["B","A"] collide.
 *   - Sort object keys so {b:1,a:2} and {a:2,b:1} collide.
 */
export function canonicalKey(query: AuctionSearchQuery): string {
  const pairs = Object.entries(query)
    .filter(([, v]) => v !== undefined && v !== null)
    .map(([k, v]) => [k, Array.isArray(v) ? [...v].sort() : v] as const)
    .sort(([a], [b]) => a.localeCompare(b));
  return JSON.stringify(Object.fromEntries(pairs));
}
