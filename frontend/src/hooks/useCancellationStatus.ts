"use client";

import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { getCancellationStatus } from "@/lib/api/cancellations";
import type { CancellationStatusResponse } from "@/types/cancellation";

/**
 * React Query keys for the cancellation domain. Exported so the cancel
 * modal, the dashboard banner, and any future WS handlers can invalidate
 * without reaching into the hook internals.
 *
 * <p>Banner-state freshness in sub-spec 2 relies on
 * <ul>
 *   <li>{@code staleTime: 30_000} on {@code status} so a quick re-open of
 *       the cancel modal doesn't refetch unnecessarily.</li>
 *   <li>Window-focus refetch on {@code /me} (configured in
 *       {@code useCurrentUser}) when the seller tabs back from SL after
 *       paying.</li>
 * </ul>
 * No WS push for {@code PENALTY_CLEARED} ships in sub-spec 2 — that's
 * gated on the user-targeted-queue infrastructure deferred elsewhere.
 */
export const cancellationKeys = {
  status: ["me", "cancellation-status"] as const,
  historyAll: ["me", "cancellation-history"] as const,
  history: (page: number, size: number) =>
    [...cancellationKeys.historyAll, page, size] as const,
};

/**
 * Read hook for the cancel-modal preview envelope. Cached for 30s so
 * back-to-back modal opens don't refetch — the spec calls out staleness
 * tolerance because the answer rarely changes mid-session.
 */
export function useCancellationStatus(): UseQueryResult<CancellationStatusResponse> {
  return useQuery<CancellationStatusResponse>({
    queryKey: cancellationKeys.status,
    queryFn: getCancellationStatus,
    staleTime: 30_000,
  });
}
