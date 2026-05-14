"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { realtyGroupBulkCommissionApi } from "@/lib/api/realtyGroupBulkCommission";
import { realtyQueryKeys } from "./useRealtyGroups";
import type { BulkCommissionRatesRequest } from "@/types/realty";

/**
 * Bulk-edit per-member commission rates on a realty group. Atomic — the
 * server rolls back the whole batch on any single failure (member not in
 * group, negative rate, suspended group).
 *
 * <p>Invalidates the whole realty prefix on success so every cached slice
 * of the group (members tab via {@code group-by-slug}, the publicId-keyed
 * group, members list, analytics, my-groups summary) refetches against
 * the new rates immediately. Mirrors the {@code invalidateAll} pattern
 * used by every other realty mutation — the earlier scoped invalidation
 * used a non-existent {@code ["realty", "groups", id]} prefix (plural
 * "groups") that matched nothing, so the members tab stayed stale until
 * the user refreshed.
 */
export function useBulkCommissionEdit(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: BulkCommissionRatesRequest) =>
      realtyGroupBulkCommissionApi.update(groupPublicId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: realtyQueryKeys.all });
    },
  });
}
