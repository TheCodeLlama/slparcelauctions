"use client";

import { useQuery } from "@tanstack/react-query";
import { getAuction } from "@/lib/api/auctions";
import { isActivatePollingStop } from "@/lib/listing/auctionStatus";
import type { SellerAuctionResponse } from "@/types/auction";

/**
 * Key used by the activate page's polling cache entry. Kept as a
 * const-returning function so both {@link useActivateAuction} and
 * collaborators (e.g., VerificationMethodPicker, CancelListingModal) can
 * synchronize cache writes after a mutation without duplicating the
 * string literal.
 */
export function activateAuctionKey(id: number | string): readonly unknown[] {
  return ["auction", String(id), "activate"] as const;
}

/**
 * Polls {@code GET /auctions/{id}} for the activate-flow page.
 *
 * Polling cadence: every 5 seconds when the status is still in-flight
 * (DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED), and
 * stopped the moment the auction reaches any status in
 * {@link isActivatePollingStop} (ACTIVE, CANCELLED, SUSPENDED, ENDED,
 * EXPIRED, COMPLETED, DISPUTED, ESCROW_*, TRANSFER_PENDING). This
 * mirrors spec §5.7 — the activate page has no more work once any of
 * those statuses is reached, so there's no reason to keep hitting the
 * server.
 *
 * {@code refetchIntervalInBackground: false} pauses the interval when
 * the tab is hidden so a dozen open tabs don't hammer the API. When the
 * tab returns to the foreground we also refetch on window focus to
 * catch up if the backend advanced while we were asleep.
 */
export function useActivateAuction(id: number | string) {
  return useQuery<SellerAuctionResponse>({
    queryKey: activateAuctionKey(id),
    queryFn: () => getAuction(id),
    refetchInterval: (q) => {
      const data = q.state.data as SellerAuctionResponse | undefined;
      if (!data) return 5_000;
      return isActivatePollingStop(data.status) ? false : 5_000;
    },
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  });
}
