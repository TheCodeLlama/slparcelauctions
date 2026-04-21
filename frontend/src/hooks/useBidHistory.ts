"use client";

import { useQuery } from "@tanstack/react-query";
import { getBidHistory } from "@/lib/api/auctions";
import type { BidHistoryEntry } from "@/types/auction";
import type { Page } from "@/types/page";

/**
 * Bid-history cache key factory. One entry per (auctionId, page) — page 0
 * is WS-driven (merged from {@code BidSettlementEnvelope.newBids}); pages 1+
 * are static until the caller pages into them. Exposed so the envelope
 * merger and reconnect-reconcile can target the same key.
 */
export function bidHistoryKey(
  auctionId: number,
  page: number,
): readonly unknown[] {
  return ["auction", auctionId, "bids", page] as const;
}

/**
 * React Query hook for {@code GET /api/v1/auctions/{id}/bids?page=&size=}.
 *
 * Page 0 seeds from the server component's initial fetch; subsequent pages
 * fetch on demand when the caller bumps the {@code page} argument. The 30s
 * stale-time matches the other auction-scoped queries so cross-cache
 * invalidation on reconnect reconciles together.
 */
export function useBidHistory(
  auctionId: number,
  page: number,
  initialData?: Page<BidHistoryEntry>,
) {
  return useQuery<Page<BidHistoryEntry>>({
    queryKey: bidHistoryKey(auctionId, page),
    queryFn: () => getBidHistory(auctionId, { page, size: 20 }),
    // Only seed page 0 from the server component — pages 1+ are lazy and
    // must fetch on first access so we never hand them a stale snapshot.
    initialData: page === 0 ? initialData : undefined,
    staleTime: 30_000,
  });
}
