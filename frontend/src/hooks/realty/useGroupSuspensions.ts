"use client";
import { useQuery } from "@tanstack/react-query";
import { realtyGroupModerationApi } from "@/lib/api/realtyGroupModeration";

/**
 * Query key for the suspension history of a realty group. Exported so
 * mutation hooks (issue/lift) can invalidate without duplicating the tuple.
 */
export function realtyGroupSuspensionsQueryKey(groupPublicId: string) {
  return ["realty", "admin", "suspensions", groupPublicId] as const;
}

/**
 * List suspension history (active + lifted + expired) for a realty group.
 * Drives the admin detail page's Suspensions tab.
 */
export function useGroupSuspensions(groupPublicId: string | undefined) {
  return useQuery({
    queryKey: realtyGroupSuspensionsQueryKey(groupPublicId ?? ""),
    queryFn: () => realtyGroupModerationApi.list(groupPublicId!),
    enabled: !!groupPublicId,
    staleTime: 5_000,
  });
}
