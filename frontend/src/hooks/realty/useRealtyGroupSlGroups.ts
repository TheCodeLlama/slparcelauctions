"use client";
import { useQuery } from "@tanstack/react-query";
import { realtySlGroupApi } from "@/lib/api/realtySlGroup";

/**
 * Query key for the SL-group registrations attached to a realty group.
 * Exported so mutation hooks (and ad-hoc callers, e.g. on a WebSocket
 * verification-success event) can invalidate without duplicating the
 * key tuple.
 */
export function realtyGroupSlGroupsQueryKey(groupPublicId: string) {
  return ["realty", "sl-groups", groupPublicId] as const;
}

/**
 * List the SL-group registrations on the given realty group. Drives the
 * settings-tab "Linked SL groups" list and the listing-flow eligibility
 * preview.
 */
export function useRealtyGroupSlGroups(groupPublicId: string | undefined) {
  return useQuery({
    queryKey: realtyGroupSlGroupsQueryKey(groupPublicId ?? ""),
    queryFn: () => realtySlGroupApi.list(groupPublicId!),
    enabled: !!groupPublicId,
    staleTime: 5_000,
  });
}
