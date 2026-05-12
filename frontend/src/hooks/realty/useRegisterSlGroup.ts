"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { realtySlGroupApi } from "@/lib/api/realtySlGroup";
import { realtyGroupSlGroupsQueryKey } from "./useRealtyGroupSlGroups";

/**
 * Co-located mutation hooks for the SL-group registration lifecycle. They
 * all invalidate the same {@code ["realty", "sl-groups", groupPublicId]}
 * query so the list re-renders the new row state (pending → verified,
 * removed, polled-attempt-count bump) without the caller having to do
 * anything beyond {@code .mutate()}.
 *
 * <p>No toast wiring here on purpose — these mutations land inside dialogs
 * that own their own success/error UI (verification code panel, "remove?"
 * confirm, "recheck now" button). The component decides what to show on
 * settle.
 */

export function useRegisterSlGroup(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (slGroupUuid: string) =>
      realtySlGroupApi.register(groupPublicId, { slGroupUuid }),
    onSuccess: () =>
      qc.invalidateQueries({
        queryKey: realtyGroupSlGroupsQueryKey(groupPublicId),
      }),
  });
}

export function useUnregisterSlGroup(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (slGroupPublicId: string) =>
      realtySlGroupApi.unregister(groupPublicId, slGroupPublicId),
    onSuccess: () =>
      qc.invalidateQueries({
        queryKey: realtyGroupSlGroupsQueryKey(groupPublicId),
      }),
  });
}

export function useRecheckSlGroup(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (slGroupPublicId: string) =>
      realtySlGroupApi.recheck(groupPublicId, slGroupPublicId),
    onSuccess: () =>
      qc.invalidateQueries({
        queryKey: realtyGroupSlGroupsQueryKey(groupPublicId),
      }),
  });
}
