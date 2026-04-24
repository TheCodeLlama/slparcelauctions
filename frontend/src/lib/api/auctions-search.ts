import { api } from "@/lib/api";
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
 * Featured-row categories shipped by the homepage. {@code ending-soon}
 * surfaces active auctions with closest endsAt; {@code just-listed} the
 * most-recently-approved actives; {@code most-active} those with the
 * highest bidderCount in the past 24h.
 */
export type FeaturedCategory = "ending-soon" | "just-listed" | "most-active";

/**
 * GET /api/v1/auctions/featured/{category}. Intended for the landing page's
 * three carousels; returns a flat {@code content} list (no pagination) so
 * the caller can render the strip without a second fetch.
 */
export function fetchFeatured(
  category: FeaturedCategory,
): Promise<{ content: AuctionSearchResultDto[] }> {
  return api.get(`/api/v1/auctions/featured/${category}`);
}
