"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { realtyGroupModerationApi } from "@/lib/api/realtyGroupModeration";
import type { IssueSuspensionRequest } from "@/types/realty";
import { realtyGroupSuspensionsQueryKey } from "./useGroupSuspensions";

/**
 * Issue a new suspension or permanent ban. On success, invalidates the
 * suspension history query so the freshly-created row appears in the
 * admin tab without an extra page refresh.
 *
 * <p>No toast wiring here — the modal that owns this mutation handles its
 * own success / error UI (the issue flow may chain into the bulk-listings
 * confirm step depending on the {@code bulkSuspendListings} flag).
 */
export function useIssueGroupSuspension(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: IssueSuspensionRequest) =>
      realtyGroupModerationApi.issue(groupPublicId, body),
    onSuccess: () =>
      qc.invalidateQueries({
        queryKey: realtyGroupSuspensionsQueryKey(groupPublicId),
      }),
  });
}
