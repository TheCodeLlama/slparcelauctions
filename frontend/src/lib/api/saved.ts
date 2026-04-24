import { api } from "@/lib/api";
import { searchParamsFromQuery } from "@/lib/search/url-codec";
import type {
  AuctionSearchQuery,
  SavedIdsResponse,
  SearchResponse,
} from "@/types/search";

/**
 * GET /api/v1/me/saved/ids — returns the full set of auction IDs the caller
 * has saved. The Curator Tray hydrates hearts across every ListingCard on
 * the page from this single response (vs. per-card lookups).
 */
export function fetchSavedIds(): Promise<SavedIdsResponse> {
  return api.get<SavedIdsResponse>("/api/v1/me/saved/ids");
}

/**
 * GET /api/v1/me/saved/auctions — paginated list of saved auctions, fully
 * hydrated with the same {@link AuctionSearchResultDto} shape as the browse
 * search endpoint. Accepts the same {@link AuctionSearchQuery} surface so
 * the URL codec and the canonical-key hashing are shared with /browse.
 */
export function fetchSavedAuctions(
  query: AuctionSearchQuery,
): Promise<SearchResponse> {
  const params = searchParamsFromQuery(query);
  const qs = params.toString();
  const path = "/api/v1/me/saved/auctions" + (qs ? `?${qs}` : "");
  return api.get<SearchResponse>(path);
}

/**
 * POST /api/v1/me/saved — save an auction. Returns the persisted row with
 * the server-stamped {@code savedAt} timestamp. Errors:
 *   - 409 {@code SAVED_LIMIT_REACHED} — tray already holds 500 saved rows.
 *   - 403 {@code CANNOT_SAVE_PRE_ACTIVE} — auction status is pre-ACTIVE.
 *   - 404 — auction does not exist.
 */
export function saveAuction(
  auctionId: number,
): Promise<{ auctionId: number; savedAt: string }> {
  return api.post<{ auctionId: number; savedAt: string }>(
    "/api/v1/me/saved",
    { auctionId },
  );
}

/**
 * DELETE /api/v1/me/saved/{auctionId} — idempotent unsave. Server returns
 * 204 on success (and also on "not saved" — no 404 branch for the happy
 * path).
 */
export function unsaveAuction(auctionId: number): Promise<void> {
  return api.delete<void>(`/api/v1/me/saved/${auctionId}`);
}
