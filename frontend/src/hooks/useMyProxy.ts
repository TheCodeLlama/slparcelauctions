"use client";

import { useQuery } from "@tanstack/react-query";
import { getMyProxy } from "@/lib/api/auctions";
import type { ProxyBidResponse } from "@/types/auction";

/**
 * Cache key for the caller's most-recent proxy bid on a given auction.
 * {@code null} is a valid cached value — "no proxy" is semantically meaningful
 * for the BidPanel variant selector, not a gap that should trigger a refetch.
 */
export function myProxyKey(auctionId: number): readonly unknown[] {
  return ["auction", auctionId, "my-proxy"] as const;
}

/**
 * React Query hook for {@code GET /api/v1/auctions/{id}/proxy-bid}. Returns
 * {@code null} when the caller has no proxy (the API helper collapses 404 to
 * null — see {@link getMyProxy}).
 *
 * Disabled by default via the {@code enabled} option so the
 * AuctionDetailClient shell can gate the fetch on "authenticated non-seller
 * viewer" — anonymous and seller-viewer variants of the page never hit this
 * endpoint. Invalidated inline by the WS envelope handler whenever a
 * settlement arrives (a competitor's bid may have exhausted our proxy).
 */
export function useMyProxy(
  auctionId: number,
  options: { enabled?: boolean } = {},
) {
  return useQuery<ProxyBidResponse | null>({
    queryKey: myProxyKey(auctionId),
    queryFn: () => getMyProxy(auctionId),
    enabled: options.enabled ?? true,
    staleTime: 30_000,
  });
}
