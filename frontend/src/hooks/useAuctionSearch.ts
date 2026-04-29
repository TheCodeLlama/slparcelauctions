import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { searchAuctions } from "@/lib/api/auctions-search";
import { canonicalKey } from "@/lib/search/canonical-key";
import type { AuctionSearchQuery, SearchResponse } from "@/types/search";

type Opts = { initialData?: SearchResponse };

/**
 * React Query wrapper around {@link searchAuctions}. Cache key is the
 * canonical JSON hash of the query so equivalent filter sets share cache
 * entries regardless of key or array order.
 *
 * {@code initialData} is used by the SSR browse page to avoid a client-side
 * refetch on first render. {@code staleTime=30s} matches the backend's
 * Cache-Control max-age so the client does not thrash the server when the
 * user flips back and forth between filter states they just visited.
 */
export function useAuctionSearch(
  query: AuctionSearchQuery,
  opts: Opts = {},
): UseQueryResult<SearchResponse> {
  return useQuery({
    queryKey: ["auctions", "search", canonicalKey(query)],
    queryFn: () => searchAuctions(query),
    initialData: opts.initialData,
    staleTime: 30_000,
  });
}
