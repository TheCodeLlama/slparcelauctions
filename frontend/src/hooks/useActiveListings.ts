"use client";

import { useQuery } from "@tanstack/react-query";
import {
  getActiveListingsForUser,
  type GetActiveListingsForUserParams,
} from "@/lib/api/auctions";
import type { PublicAuctionResponse } from "@/types/auction";
import type { Page } from "@/types/page";

/**
 * Query-key factory for the public-profile active-listings grid. Scoped by
 * {@code userId} + pagination params so a second visit to a different
 * profile doesn't hit the cache from the previous one. Exposed so
 * collaborators (e.g. the public profile component's "View all" handler)
 * can invalidate without re-importing the string literal.
 */
export function activeListingsKey(
  userId: number | string,
  params: GetActiveListingsForUserParams = {},
): readonly unknown[] {
  const page = params.page ?? 0;
  const size = params.size ?? 6;
  return ["user", userId, "active-listings", { page, size }] as const;
}

/**
 * React Query wrapper around {@link getActiveListingsForUser}. Spec §14
 * renders up to 6 cards on the public profile — this hook is the single
 * read path. 60s staleTime matches the public-profile cadence (slower
 * than the bidder dashboard since listings churn more slowly than bids).
 */
export function useActiveListings(
  userId: number | string | null | undefined,
  params: GetActiveListingsForUserParams = {},
) {
  return useQuery<Page<PublicAuctionResponse>>({
    queryKey: activeListingsKey(userId ?? "anonymous", params),
    queryFn: () => getActiveListingsForUser(userId as number, params),
    enabled: userId != null,
    staleTime: 60_000,
  });
}
