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
