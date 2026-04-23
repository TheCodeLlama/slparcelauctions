"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { listMyAuctions } from "@/lib/api/auctions";
import { FILTER_GROUPS } from "@/lib/listing/auctionStatus";
import type { AuctionStatus, SellerAuctionResponse } from "@/types/auction";

/**
 * User-facing filter buckets that the My Listings tab renders as chips.
 * "All" shows everything; the remaining keys map to {@link FILTER_GROUPS}
 * from {@code lib/listing/auctionStatus.ts} (spec §6.2).
 *
 * Order is the intended chip order in the UI (Suspended is conditional —
 * {@link useMyListingsSuspendedCount} decides whether to render it).
 */
export const MY_LISTINGS_FILTERS = [
  "All",
  "Active",
  "Drafts",
  "Ended",
  "Cancelled",
  "Suspended",
] as const;

export type MyListingsFilter = (typeof MY_LISTINGS_FILTERS)[number];

/**
 * Base query key for the My Listings dashboard tab. Kept as a factory so
 * CancelListingModal and other collaborators can invalidate without
 * reimporting the string literal.
 */
export function myListingsKey(filter: MyListingsFilter = "All") {
  return ["my-listings", { filter }] as const;
}

/**
 * Frontend-side status bucket filter.
 *
 * The backend {@code GET /api/v1/users/me/auctions} currently ignores
 * status query params — {@link listMyAuctions} passes them for forward
 * compatibility but the server returns every auction owned by the
 * authenticated seller regardless. Rather than trigger a round-trip per
 * filter chip, we fetch once and filter client-side. The list is bounded
 * by a single seller's lifetime listings (Phase 1) so this is cheap.
 */
function statusesFor(filter: MyListingsFilter): AuctionStatus[] | null {
  if (filter === "All") return null;
  return FILTER_GROUPS[filter] ?? null;
}

function applyFilter(
  rows: SellerAuctionResponse[],
  filter: MyListingsFilter,
): SellerAuctionResponse[] {
  const allowed = statusesFor(filter);
  if (!allowed) return rows;
  const set = new Set(allowed);
  return rows.filter((a) => set.has(a.status));
}

export interface UseMyListingsResult {
  /** Filtered rows matching the selected bucket. */
  listings: SellerAuctionResponse[];
  /** All listings (unfiltered). Used to derive empty-state + chip counts. */
  all: SellerAuctionResponse[];
  isLoading: boolean;
  isFetching: boolean;
  isError: boolean;
  error: unknown;
  refetch: () => void;
}

/**
 * Hook for the My Listings dashboard tab.
 *
 * Fetches all auctions the authenticated seller owns, then filters them
 * by the selected bucket on the client (see {@link statusesFor}).
 * Auto-refetches every 30 seconds while the tab is visible — the cadence
 * is intentionally slower than the activate page's 5s because this view
 * is a browsing surface, not a waiting-for-transition surface. The
 * interval pauses when the tab is backgrounded ({@code
 * refetchIntervalInBackground: false}) to avoid wasted polls.
 */
export function useMyListings(
  filter: MyListingsFilter = "All",
): UseMyListingsResult {
  const query = useQuery<SellerAuctionResponse[]>({
    queryKey: ["my-listings"],
    queryFn: () => listMyAuctions(),
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  });

  const all = useMemo(() => query.data ?? [], [query.data]);
  const listings = useMemo(() => applyFilter(all, filter), [all, filter]);

  return {
    listings,
    all,
    isLoading: query.isLoading,
    isFetching: query.isFetching,
    isError: query.isError,
    error: query.error,
    refetch: () => {
      query.refetch();
    },
  };
}

/**
 * Returns the count of SUSPENDED listings for the authenticated seller.
 *
 * Derived from the same underlying query cache key as
 * {@link useMyListings} — so switching chips does not trigger extra
 * fetches. The caller renders the "Suspended" filter chip only when this
 * count is > 0 (spec §6.2).
 */
export function useMyListingsSuspendedCount(): number {
  const query = useQuery<SellerAuctionResponse[]>({
    queryKey: ["my-listings"],
    queryFn: () => listMyAuctions(),
    // We do not poll from this secondary view — the primary
    // useMyListings() hook keeps the shared cache warm.
    refetchInterval: false,
    refetchIntervalInBackground: false,
  });
  return (query.data ?? []).filter((a) => a.status === "SUSPENDED").length;
}
