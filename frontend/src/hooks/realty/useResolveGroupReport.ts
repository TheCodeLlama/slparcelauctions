"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminRealtyGroupReportsApi } from "@/lib/api/realtyGroupReports";
import type {
  AdminDismissReportRequest,
  AdminResolveReportRequest,
} from "@/types/realty";
import { realtyGroupReportsKeys } from "./useGroupReports";

/**
 * Mark a report as RESOLVED. Invalidates the queue + per-group + detail
 * queries so the post-resolve row state propagates without an extra
 * round-trip.
 *
 * <p>{@code escalateTo} is informational only — the backend never acts on
 * it. The frontend reads it to decide whether to chain into a suspension
 * modal post-resolve.
 */
export function useResolveGroupReport(reportPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: AdminResolveReportRequest) =>
      adminRealtyGroupReportsApi.resolve(reportPublicId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: realtyGroupReportsKeys.all() });
    },
  });
}

/**
 * Mark a report as DISMISSED. Bumps the reporter's dismissed-count
 * counter server-side; invalidates the same key set as
 * {@link useResolveGroupReport}.
 */
export function useDismissGroupReport(reportPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: AdminDismissReportRequest) =>
      adminRealtyGroupReportsApi.dismiss(reportPublicId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: realtyGroupReportsKeys.all() });
    },
  });
}
