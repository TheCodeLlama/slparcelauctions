"use client";
import {
  keepPreviousData,
  useQuery,
  type UseQueryResult,
} from "@tanstack/react-query";
import {
  getBrowseGroups,
  type BrowseGroupCard,
  type BrowseGroupsParams,
} from "@/lib/api/realtyGroupsBrowse";
import type { Page } from "@/types/page";

/**
 * TanStack Query hook over the public {@code GET /api/v1/realty-groups}
 * directory endpoint (spec section 6.1).
 *
 * <p>Cache key includes every normalized input so a change to {@code q},
 * {@code page}, {@code size}, or {@code sort} triggers a fresh fetch under a
 * distinct key. Inputs are normalized (defaults applied) so callers that omit
 * a field share a cache slot with callers that pass the explicit default.
 *
 * <p>{@code staleTime} is 30s — the cards' rating / active-listings counts
 * move slowly enough that a half-minute window of cached results is
 * comfortable, and pagination feels instant on cached pages.
 *
 * <p>{@code placeholderData: keepPreviousData} keeps the prior page visible
 * while the next page is fetching, so the grid does not collapse to an empty
 * state between clicks of the pagination strip.
 */
export function useBrowseGroups(
  params: BrowseGroupsParams,
): UseQueryResult<Page<BrowseGroupCard>> {
  const q = params.q ?? "";
  const page = params.page ?? 0;
  const size = params.size ?? 20;
  const sort = params.sort ?? "RATING";

  return useQuery({
    queryKey: ["realty-groups", "browse", { q, page, size, sort }] as const,
    queryFn: () => getBrowseGroups(params),
    staleTime: 30_000,
    placeholderData: keepPreviousData,
  });
}
