"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { realtyGroupSlGroupAdminApi } from "@/lib/api/realtyGroupSlGroupAdmin";
import type { AckDriftRequest } from "@/types/realty";
import { realtyGroupSlGroupsQueryKey } from "./useRealtyGroupSlGroups";

/**
 * Acknowledge drift on an SL-group registration. Rolls the founder
 * snapshot forward when a new value was observed; clears the
 * drift_detected_at / drift_reason fields. Returns the post-ack row.
 *
 * <p>Invalidates the SL-groups list so the cleared drift fields render
 * immediately.
 */
export function useSlGroupAckDrift(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      slGroupPublicId,
      body,
    }: {
      slGroupPublicId: string;
      body?: AckDriftRequest;
    }) =>
      realtyGroupSlGroupAdminApi.ackDrift(
        groupPublicId,
        slGroupPublicId,
        body ?? {},
      ),
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: realtyGroupSlGroupsQueryKey(groupPublicId),
      });
    },
  });
}
