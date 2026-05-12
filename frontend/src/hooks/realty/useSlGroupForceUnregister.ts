"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { realtyGroupSlGroupAdminApi } from "@/lib/api/realtyGroupSlGroupAdmin";
import type { ForceUnregisterRequest } from "@/types/realty";
import { realtyGroupSlGroupsQueryKey } from "./useRealtyGroupSlGroups";

/**
 * Force-unregister an SL group registration. Bypasses the active-listings
 * gate (when {@code force=true}) and cascades any in-flight case-3
 * listings into the bulk-suspend pipeline. {@code reason} is required.
 *
 * <p>Returns 204 — consumers re-query the SL-groups list to see the row
 * disappear (or appear with {@code unregisteredAt} stamped on the admin
 * row view).
 */
export function useSlGroupForceUnregister(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      slGroupPublicId,
      body,
      force = true,
    }: {
      slGroupPublicId: string;
      body: ForceUnregisterRequest;
      force?: boolean;
    }) =>
      realtyGroupSlGroupAdminApi.forceUnregister(
        groupPublicId,
        slGroupPublicId,
        body,
        force,
      ),
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: realtyGroupSlGroupsQueryKey(groupPublicId),
      });
    },
  });
}
