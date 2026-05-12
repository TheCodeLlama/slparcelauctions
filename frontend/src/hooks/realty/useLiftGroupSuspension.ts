"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { realtyGroupModerationApi } from "@/lib/api/realtyGroupModeration";
import type { LiftSuspensionRequest } from "@/types/realty";
import { realtyGroupSuspensionsQueryKey } from "./useGroupSuspensions";

/**
 * Lift an active suspension. The mutation function takes both the
 * suspension public id and the request body so a single hook instance
 * (bound to a single {@code groupPublicId}) can lift any row in the
 * group's history.
 *
 * <p>Invalidates the suspension list on success so the LIFTED state
 * shows immediately.
 */
export function useLiftGroupSuspension(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      suspensionPublicId,
      body,
    }: {
      suspensionPublicId: string;
      body: LiftSuspensionRequest;
    }) =>
      realtyGroupModerationApi.lift(groupPublicId, suspensionPublicId, body),
    onSuccess: () =>
      qc.invalidateQueries({
        queryKey: realtyGroupSuspensionsQueryKey(groupPublicId),
      }),
  });
}
