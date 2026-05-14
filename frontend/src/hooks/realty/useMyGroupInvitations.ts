"use client";
import { useQuery } from "@tanstack/react-query";
import { realtyGroupsApi } from "@/lib/api/realtyGroups";
import { realtyQueryKeys } from "./useRealtyGroups";

/**
 * Pending realty-group invitations addressed to the caller.
 *
 * Distinct from `useMyInvitations` in `useRealtyGroups.ts`:
 * - Same backend endpoint and same query key (so the cache is shared across
 *   call sites — bell badge, recipient page, dropdown indicator).
 * - Longer `staleTime` (60s) to match the existing notification-count poll
 *   cadence per spec section 5.2; the strip on the legacy dashboard page
 *   wanted near-real-time freshness, but the dedicated recipient page +
 *   badge surfaces are content the user navigates _to_, so a 60s window
 *   eliminates a thundering-herd of identical fetches when the dropdown
 *   opens and the page mounts back-to-back.
 *
 * Mutations that change invitation state (`useAcceptInvitation`,
 * `useDeclineInvitation`) invalidate the shared query key, so the list
 * still updates immediately after a user action.
 */
export function useMyGroupInvitations() {
  return useQuery({
    queryKey: realtyQueryKeys.myInvitations(),
    queryFn: () => realtyGroupsApi.myInvitations(),
    staleTime: 60_000,
  });
}
