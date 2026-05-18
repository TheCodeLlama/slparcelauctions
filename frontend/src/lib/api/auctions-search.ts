import { api, isApiError } from "@/lib/api";
import { searchParamsFromQuery } from "@/lib/search/url-codec";
import type {
  AuctionSearchQuery,
  AuctionSearchResultDto,
  SearchResponse,
} from "@/types/search";

/**
 * GET /api/v1/auctions/search — the canonical browse / discover endpoint.
 * Defaults that match the server's baseline (sort=newest, page=0, size=24)
 * are dropped from the URL by {@link searchParamsFromQuery} so the wire
 * query stays short.
 *
 * Errors bubble as {@link ApiError}. 429 rate-limit responses preserve the
 * {@code Retry-After} header on {@code problem}; callers may surface a
 * "slow down, please" toast when {@code e.status === 429}.
 */
export function searchAuctions(
  query: AuctionSearchQuery,
): Promise<SearchResponse> {
  const params = searchParamsFromQuery(query);
  const qs = params.toString();
  const path = "/api/v1/auctions/search" + (qs ? `?${qs}` : "");
  return api.get<SearchResponse>(path);
}

/**
 * An empty, well-formed {@link SearchResponse}. Used by
 * {@link resolveBrowseInitialData} so the SSR browse page can render
 * {@code <BrowseShell>} with zero results instead of passing
 * {@code undefined} into a component that maps over {@code content}.
 */
function emptySearchResponse(query: AuctionSearchQuery): SearchResponse {
  return {
    content: [],
    page: query.page ?? 0,
    size: query.size ?? 24,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true,
  };
}

/**
 * Result of an SSR-side browse fetch attempt. {@code data} is always a
 * well-formed (possibly empty) {@link SearchResponse}; {@code errorCode}
 * carries the backend ProblemDetail {@code code} (e.g.
 * {@code REGION_NOT_FOUND}) when a client/filter error was swallowed so
 * the sidebar can surface it inline.
 */
export type BrowseInitialData = {
  data: SearchResponse;
  errorCode?: string;
};

/**
 * SSR-safe wrapper around {@link searchAuctions} for the /browse Server
 * Component. The bare {@code await searchAuctions(query)} crashed the
 * whole route with a 500 whenever a filter value was rejected by the
 * backend (HTTP 400 — e.g. an unknown {@code near_region} →
 * {@code REGION_NOT_FOUND}). The backend is correct to reject; the page
 * must not die for it.
 *
 * Behaviour by failure class:
 *   - 4xx {@link ApiError} (client / filter mistake): swallow it, return
 *     an empty result set plus the ProblemDetail {@code code} so the
 *     sidebar shows an inline message. The page still renders.
 *   - 5xx / network / non-ApiError (genuine server outage or bug):
 *     rethrow. The route's {@code error.tsx} boundary owns that case so
 *     a real outage stays visibly distinct from a filter typo.
 *
 * Kept here (next to {@code searchAuctions}, mirroring the existing
 * {@code auctions-search.test.ts} pattern) so the catch/normalize logic
 * is unit-testable without rendering the async RSC.
 */
export async function resolveBrowseInitialData(
  query: AuctionSearchQuery,
): Promise<BrowseInitialData> {
  try {
    const data = await searchAuctions(query);
    return { data };
  } catch (e) {
    if (isApiError(e) && e.status >= 400 && e.status < 500) {
      const code =
        typeof e.problem.code === "string" ? e.problem.code : undefined;
      return { data: emptySearchResponse(query), errorCode: code };
    }
    // 5xx, network failure, or anything that isn't an ApiError: this is
    // a genuine outage/bug, not a filter mistake. Let the route error
    // boundary handle it so the two stay distinguishable to the user.
    throw e;
  }
}

/**
 * Homepage rail categories. {@code featured} is admin-curated with auto-fill
 * by current_bid; {@code ending-soon} surfaces actives with closest endsAt;
 * {@code trending} ranks by a 24h weighted score (bids * 2 + saves).
 */
export type FeaturedCategory = "featured" | "ending-soon" | "trending";

/**
 * GET /api/v1/auctions/rails/{category}. Intended for the landing page's
 * three rails; returns a flat {@code content} list (no pagination) so the
 * caller can render the strip without a second fetch.
 */
export function fetchFeatured(
  category: FeaturedCategory,
): Promise<{ content: AuctionSearchResultDto[] }> {
  return api.get(`/api/v1/auctions/rails/${category}`);
}
