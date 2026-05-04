"use client";

import { useQuery } from "@tanstack/react-query";
import { getAuction } from "@/lib/api/auctions";
import type {
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";

/**
 * Auction cache key. Exported so collaborators (the WebSocket envelope merger,
 * reconnect reconcile, cancel-proxy mutation) can invalidate / read / write
 * the same entry without duplicating the string literal.
 */
export function auctionKey(publicId: string): readonly unknown[] {
  return ["auction", publicId] as const;
}

/**
 * Union type returned by {@code GET /api/v1/auctions/{publicId}} — the backend
 * picks the DTO based on viewer identity (seller → full, anyone else → public).
 * See {@link getAuction} for the contract and {@link PublicAuctionResponse} /
 * {@link SellerAuctionResponse} for the field-level differences.
 */
export type AuctionData = PublicAuctionResponse | SellerAuctionResponse;

/**
 * React Query hook that wraps {@code GET /api/v1/auctions/{publicId}}.
 *
 * Seeded from the server component via {@code initialData} so the first paint
 * renders without a loading flash. The WebSocket envelope handler in
 * {@code AuctionDetailClient} is the primary updater — this query refetches on
 * reconnect-reconcile and window focus, but not on an interval (spec §5: "WS-
 * first with REST reconcile on reconnect. No polling.").
 */
export function useAuction(publicId: string, initialData?: AuctionData) {
  return useQuery<AuctionData>({
    queryKey: auctionKey(publicId),
    queryFn: () => getAuction(publicId),
    initialData,
    staleTime: 30_000,
  });
}
