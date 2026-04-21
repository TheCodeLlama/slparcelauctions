import { api, ApiError, isApiError } from "@/lib/api";
import type { Page } from "@/types/page";
import type {
  AuctionCancelRequest,
  AuctionCreateRequest,
  AuctionUpdateRequest,
  AuctionVerifyRequest,
  BidHistoryEntry,
  BidResponse,
  ProxyBidResponse,
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";

/**
 * Auction CRUD client. Mirrors AuctionController endpoints. All endpoints
 * require an SL-verified seller — unverified callers get 403 problem details
 * which bubble up as ApiError.
 *
 * verificationMethod is intentionally not accepted on create/update — it is
 * selected by the seller at activate time via {@link triggerVerify} (see
 * sub-spec 2 §7.2 and AuctionVerifyRequest).
 */
export function createAuction(
  body: AuctionCreateRequest,
): Promise<SellerAuctionResponse> {
  return api.post<SellerAuctionResponse>("/api/v1/auctions", body);
}

export function updateAuction(
  id: number | string,
  body: AuctionUpdateRequest,
): Promise<SellerAuctionResponse> {
  return api.put<SellerAuctionResponse>(`/api/v1/auctions/${id}`, body);
}

/**
 * GET /api/v1/auctions/{id}. Backend returns either a
 * {@link SellerAuctionResponse} (when the caller owns the auction) or a
 * {@link PublicAuctionResponse} (all other viewers, including anonymous) —
 * the same endpoint path with a server-side DTO discriminator on status +
 * viewer identity. The union type matches that contract.
 *
 * Consumers that need seller-only fields (verification metadata, reserve
 * price, listing-fee txn, etc.) narrow on the presence of those fields or on
 * the status enum: SellerAuctionResponse carries every status, while
 * PublicAuctionResponse collapses to `ACTIVE | ENDED`.
 */
export function getAuction(
  id: number | string,
): Promise<PublicAuctionResponse | SellerAuctionResponse> {
  return api.get<PublicAuctionResponse | SellerAuctionResponse>(
    `/api/v1/auctions/${id}`,
  );
}

/**
 * Query parameters for {@link listMyAuctions}. Defined as a type alias (not
 * an interface) so it structurally extends the api helper's
 * {@code Record<string, string | number | boolean | undefined>} contract.
 */
export type ListMyAuctionsParams = {
  status?: string;
  page?: number;
  size?: number;
};

/**
 * GET /api/v1/users/me/auctions. Returns the authenticated seller's
 * auctions. The backend currently ignores query params (returns all owned
 * auctions) — the params are kept here for forward compatibility with the
 * My Listings tab (Task 10), which will filter server-side once the backend
 * grows that capability.
 */
export function listMyAuctions(
  params: ListMyAuctionsParams = {},
): Promise<SellerAuctionResponse[]> {
  return api.get<SellerAuctionResponse[]>("/api/v1/users/me/auctions", {
    params,
  });
}

export function triggerVerify(
  id: number | string,
  body: AuctionVerifyRequest,
): Promise<SellerAuctionResponse> {
  return api.put<SellerAuctionResponse>(`/api/v1/auctions/${id}/verify`, body);
}

export function cancelAuction(
  id: number | string,
  body: AuctionCancelRequest = {},
): Promise<SellerAuctionResponse> {
  return api.put<SellerAuctionResponse>(`/api/v1/auctions/${id}/cancel`, body);
}

// ---------- Bids, proxy bids, and user-scoped active listings (Epic 04 sub-2) ----------

/**
 * Default page size for bid-history requests. Mirrors the server-component
 * seed in spec §7 — the Auction page SSR path fetches page 0 at size 20 and
 * BidHistoryList pages at 20 thereafter.
 */
const DEFAULT_BID_HISTORY_SIZE = 20;

/**
 * Default page size for user-scoped active listings. The public-profile
 * {@code ActiveListingsSection} renders up to 6 cards (md:grid-cols-2
 * lg:grid-cols-3) per spec §14; the "View all" escape hatch ships in Epic 07.
 */
const DEFAULT_ACTIVE_LISTINGS_SIZE = 6;

export type GetBidHistoryParams = {
  page?: number;
  size?: number;
};

/**
 * GET /api/v1/auctions/{id}/bids — public paged history, newest-first. No
 * auth required (bid identity is public per DESIGN.md §1589-1591). The
 * WebSocket envelope merger keeps page 0 fresh; pages 1+ are static until
 * paged into, as described in spec §10.
 */
export function getBidHistory(
  auctionId: number | string,
  params: GetBidHistoryParams = {},
): Promise<Page<BidHistoryEntry>> {
  const page = params.page ?? 0;
  const size = params.size ?? DEFAULT_BID_HISTORY_SIZE;
  return api.get<Page<BidHistoryEntry>>(
    `/api/v1/auctions/${auctionId}/bids`,
    { params: { page, size } },
  );
}

/**
 * POST /api/v1/auctions/{id}/bids — place a manual bid. Requires an
 * authenticated, SL-verified, non-seller caller; the backend holds a
 * pessimistic row lock on the auction before validating, so the standard
 * error surface (BID_TOO_LOW, AUCTION_NOT_ACTIVE, SELLER_CANNOT_BID,
 * NOT_VERIFIED) is authoritative — see BidService + spec §9 for the UI
 * mapping. Success does NOT optimistically update — the WS settlement
 * envelope drives the post-commit render per spec §9.
 */
export function placeBid(
  auctionId: number | string,
  amount: number,
): Promise<BidResponse> {
  return api.post<BidResponse>(
    `/api/v1/auctions/${auctionId}/bids`,
    { amount },
  );
}

/**
 * POST /api/v1/auctions/{id}/proxy-bid — create a new ACTIVE proxy. Triggers
 * the full resolution against any existing competitor; the resulting
 * currentBid/endsAt changes arrive via the /topic/auction/{id} settlement
 * envelope. 409 {@code PROXY_BID_ALREADY_EXISTS} if the caller already has
 * one — callers handle that by invalidating the ["auction", id, "my-proxy"]
 * cache and retrying.
 */
export function createProxy(
  auctionId: number | string,
  maxAmount: number,
): Promise<ProxyBidResponse> {
  return api.post<ProxyBidResponse>(
    `/api/v1/auctions/${auctionId}/proxy-bid`,
    { maxAmount },
  );
}

/**
 * PUT /api/v1/auctions/{id}/proxy-bid — update the caller's most recent
 * proxy. Handles both the silent cap-raise (winning ACTIVE) and resurrection
 * (EXHAUSTED → ACTIVE) branches server-side; the UI renders the post-update
 * status from the response.
 */
export function updateProxy(
  auctionId: number | string,
  maxAmount: number,
): Promise<ProxyBidResponse> {
  return api.put<ProxyBidResponse>(
    `/api/v1/auctions/${auctionId}/proxy-bid`,
    { maxAmount },
  );
}

/**
 * DELETE /api/v1/auctions/{id}/proxy-bid — cancel the caller's ACTIVE proxy.
 * 409 {@code CANNOT_CANCEL_WINNING_PROXY} if the caller is currently winning;
 * the UI disables the button in that state so the 409 is primarily a defense
 * against a stale client.
 */
export async function cancelProxy(auctionId: number | string): Promise<void> {
  await api.delete<void>(`/api/v1/auctions/${auctionId}/proxy-bid`);
}

/**
 * GET /api/v1/auctions/{id}/proxy-bid — fetch the caller's most recent
 * proxy (any status). Returns {@code null} on 404 so the caller can
 * `if (!proxy) …` cleanly instead of try/catching every invocation;
 * other errors (401, 500, …) propagate as {@link ApiError}.
 */
export async function getMyProxy(
  auctionId: number | string,
): Promise<ProxyBidResponse | null> {
  try {
    return await api.get<ProxyBidResponse>(
      `/api/v1/auctions/${auctionId}/proxy-bid`,
    );
  } catch (e) {
    if (isApiError(e) && e.status === 404) return null;
    throw e;
  }
}

export type GetActiveListingsForUserParams = {
  page?: number;
  size?: number;
};

/**
 * GET /api/v1/users/{userId}/auctions?status=ACTIVE — public, no auth
 * required. SUSPENDED and pre-ACTIVE statuses are always excluded server-
 * side regardless of requester; see spec §14. Default size = 6 to match the
 * public-profile {@code ActiveListingsSection} grid (one render page, no
 * infinite scroll).
 *
 * The {@code status=ACTIVE} filter is hardcoded because Phase 1 only exposes
 * one value. When the Browse page (Epic 07) grows additional filters the
 * signature will widen.
 */
export function getActiveListingsForUser(
  userId: number | string,
  params: GetActiveListingsForUserParams = {},
): Promise<Page<PublicAuctionResponse>> {
  const page = params.page ?? 0;
  const size = params.size ?? DEFAULT_ACTIVE_LISTINGS_SIZE;
  return api.get<Page<PublicAuctionResponse>>(
    `/api/v1/users/${userId}/auctions`,
    { params: { status: "ACTIVE", page, size } },
  );
}

// Re-export ApiError for callers that want to narrow without importing the
// low-level api module directly. Keeps the surface "one import per area".
export { ApiError };
