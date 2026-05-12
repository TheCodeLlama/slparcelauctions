"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { realtyGroupBulkCommissionApi } from "@/lib/api/realtyGroupBulkCommission";
import type { BulkCommissionRatesRequest } from "@/types/realty";

/**
 * Bulk-edit per-member commission rates on a realty group. Atomic — the
 * server rolls back the whole batch on any single failure (member not in
 * group, negative rate, suspended group).
 *
 * <p>Invalidates the group's members + analytics queries on success so
 * the table re-renders against the new rates immediately. We invalidate
 * the broad {@code ["realty", "groups", groupPublicId]} prefix to cover
 * every cached slice of the group (members tab, analytics tab, leader
 * card).
 */
export function useBulkCommissionEdit(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: BulkCommissionRatesRequest) =>
      realtyGroupBulkCommissionApi.update(groupPublicId, body),
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: ["realty", "groups", groupPublicId],
      });
      qc.invalidateQueries({
        queryKey: ["realty", "analytics", "commissions", groupPublicId],
      });
    },
  });
}
