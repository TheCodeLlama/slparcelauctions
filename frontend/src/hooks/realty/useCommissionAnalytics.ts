"use client";
import { useQuery } from "@tanstack/react-query";
import { realtyGroupCommissionAnalyticsApi } from "@/lib/api/realtyGroupCommissionAnalytics";

/**
 * Query key for the commission analytics view. Exported so the bulk
 * commission edit mutation can invalidate after a save.
 */
export function realtyGroupCommissionAnalyticsKey(groupPublicId: string) {
  return ["realty", "analytics", "commissions", groupPublicId] as const;
}

/**
 * Fetch the per-member commission analytics for a realty group. Returns
 * one row per current member (including the leader) with lifetime +
 * last-30-day commission totals in L$.
 *
 * <p>Permission gating runs server-side — leaders + holders of
 * {@code MANAGE_MEMBERS} can read; everyone else gets 403.
 */
export function useCommissionAnalytics(groupPublicId: string | undefined) {
  return useQuery({
    queryKey: realtyGroupCommissionAnalyticsKey(groupPublicId ?? ""),
    queryFn: () => realtyGroupCommissionAnalyticsApi.get(groupPublicId!),
    enabled: !!groupPublicId,
    staleTime: 30_000,
  });
}
