"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { realtyGroupSlGroupAdminApi } from "@/lib/api/realtyGroupSlGroupAdmin";
import { realtyGroupSlGroupsQueryKey } from "./useRealtyGroupSlGroups";

/**
 * Force an on-demand admin reverify pass on an SL-group registration.
 * Returns the drift outcome ({@code driftDetected}, {@code driftReason},
 * {@code currentFounderUuid}).
 *
 * <p>Invalidates the SL-groups list query on success so the row
 * re-renders against the post-recheck state without an extra request.
 */
export function useSlGroupRecheck(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (slGroupPublicId: string) =>
      realtyGroupSlGroupAdminApi.recheck(groupPublicId, slGroupPublicId),
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: realtyGroupSlGroupsQueryKey(groupPublicId),
      });
    },
  });
}
