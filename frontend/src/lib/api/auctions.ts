import { api } from "@/lib/api";
import type {
  AuctionCancelRequest,
  AuctionCreateRequest,
  AuctionUpdateRequest,
  AuctionVerifyRequest,
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

export function getAuction(id: number | string): Promise<SellerAuctionResponse> {
  return api.get<SellerAuctionResponse>(`/api/v1/auctions/${id}`);
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
