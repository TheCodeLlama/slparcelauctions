"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { getMyBids, type MyBidsFilter } from "@/lib/api/myBids";
import type { MyBidSummary } from "@/types/auction";
import type { Page } from "@/types/page";

/**
 * Query-key factory for the (paged, non-infinite) My Bids cache. One cache
 * entry per {@code (filter, page)} pair — exposed so collaborators can
 * invalidate without reimporting the string literal. {@link useMyBids}
 * itself uses {@link useInfiniteQuery} and therefore a single key per
 * filter; this helper is retained for consumers that want point access.
 */
export function myBidsKey(
  status: MyBidsFilter,
  page: number,
): readonly unknown[] {
  return ["my-bids", { status, page }] as const;
}

/**
 * Query-key factory for the infinite-pagination variant used by
 * {@link useMyBids}. Indexed by the selected filter only — the page
 * accumulator lives inside React Query rather than in component state, so
 * changing filters drops the whole cache entry rather than dribbling in
 * extra pages.
 */
export function myBidsInfiniteKey(status: MyBidsFilter): readonly unknown[] {
  return ["my-bids", "infinite", { status }] as const;
}

/**
 * Page size for the bidder dashboard. Matches the server default (spec §4)
 * and the {@link getMyBids} client helper.
 */
export const MY_BIDS_PAGE_SIZE = 20;

/**
 * Infinite-pagination wrapper around {@link getMyBids}. Spec §12 calls for
 * the My Bids tab to be REST-driven, not WebSocket-subscribed —
 * refetch-on-focus is the freshness strategy, plus a 30s staleTime so
 * filter-switching plus tab visits inside that window are cache-satisfied.
 *
 * <p>Uses {@link useInfiniteQuery} so the "Load more" button can pull
 * subsequent pages without the component owning its own accumulator — the
 * pages live in React Query cache, keyed by filter. {@code getNextPageParam}
 * returns {@code undefined} once all results are loaded, which flips
 * {@code hasNextPage} off and hides the button.
 */
export function useMyBids(status: MyBidsFilter) {
  return useInfiniteQuery<Page<MyBidSummary>>({
    queryKey: myBidsInfiniteKey(status),
    queryFn: ({ pageParam }) =>
      getMyBids({
        status,
        page: pageParam as number,
        size: MY_BIDS_PAGE_SIZE,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => {
      // {@code totalPages} is authoritative — Spring Data computes it from
      // {@code totalElements / size} so a truncated final page still agrees
      // with the element count. Returning {@code undefined} flips
      // {@code hasNextPage} off.
      const nextPage = lastPage.number + 1;
      return nextPage < lastPage.totalPages ? nextPage : undefined;
    },
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });
}

/**
 * Narrow non-infinite fetch kept for tests and one-off callers that want a
 * single page without the infinite wrapper. Not used by {@code MyBidsTab}
 * — that component consumes {@link useMyBids} above.
 */
export function useMyBidsPage(
  status: MyBidsFilter,
  page: number = 0,
) {
  return useQuery<Page<MyBidSummary>>({
    queryKey: myBidsKey(status, page),
    queryFn: () =>
      getMyBids({ status, page, size: MY_BIDS_PAGE_SIZE }),
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });
}
