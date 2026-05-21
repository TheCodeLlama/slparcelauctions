"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchAdminSupportQueueStats } from "@/lib/api/support";
import type { SupportTicketQueueStatsDto } from "@/types/support";

/**
 * Query key for the admin support queue-stats badge. Stable so the
 * sidebar badge and any future header indicator share a single cache
 * entry across the page tree.
 */
export const ADMIN_SUPPORT_STATS_KEY = ["admin-support-stats"] as const;

/**
 * Polled badge counts for the sidebar (and any other place that needs
 * a "needs admin reply" indicator). Refetches every 30 seconds so the
 * badge stays loosely-live without WebSocket plumbing; the user is
 * trusted to refresh for an authoritative count on the queue page itself.
 *
 * Gate with the caller's admin check (e.g. `useAdminSupportQueueStats(isAdmin)`)
 * so non-admin sessions don't poll an endpoint they can't hit.
 */
export function useAdminSupportQueueStats(enabled = true) {
  return useQuery<SupportTicketQueueStatsDto>({
    queryKey: ADMIN_SUPPORT_STATS_KEY,
    queryFn: fetchAdminSupportQueueStats,
    refetchInterval: 30_000,
    enabled,
  });
}
