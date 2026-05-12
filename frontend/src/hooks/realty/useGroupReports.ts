"use client";
import { useQuery } from "@tanstack/react-query";
import { adminRealtyGroupReportsApi } from "@/lib/api/realtyGroupReports";
import type {
  AdminRealtyGroupReportsFilters,
  RealtyGroupReportStatus,
} from "@/types/realty";

/**
 * Query keys for the admin realty-group reports surface. Co-located so
 * mutation hooks and the per-group narrow list share invalidation keys.
 */
export const realtyGroupReportsKeys = {
  all: () => ["realty", "admin", "reports"] as const,
  queue: (filters: AdminRealtyGroupReportsFilters) =>
    [...realtyGroupReportsKeys.all(), "queue", filters] as const,
  byGroup: (
    groupPublicId: string,
    filters: { status?: RealtyGroupReportStatus; page: number; size: number },
  ) =>
    [
      ...realtyGroupReportsKeys.all(),
      "by-group",
      groupPublicId,
      filters,
    ] as const,
  detail: (reportPublicId: string) =>
    [...realtyGroupReportsKeys.all(), "detail", reportPublicId] as const,
};

/**
 * Paginated admin queue across every group. Drives the standalone
 * {@code /admin/realty-groups/reports} page.
 */
export function useGroupReportsQueue(filters: AdminRealtyGroupReportsFilters) {
  return useQuery({
    queryKey: realtyGroupReportsKeys.queue(filters),
    queryFn: () => adminRealtyGroupReportsApi.list(filters),
    staleTime: 5_000,
  });
}

/**
 * Per-group narrow list used by the admin detail page's Reports tab.
 * Pulls only reports filed against the given group; pagination defaults
 * to page 0, size 20.
 */
export function useGroupReports(
  groupPublicId: string | undefined,
  filters: {
    status?: RealtyGroupReportStatus;
    page?: number;
    size?: number;
  } = {},
) {
  const page = filters.page ?? 0;
  const size = filters.size ?? 20;
  return useQuery({
    queryKey: realtyGroupReportsKeys.byGroup(groupPublicId ?? "", {
      status: filters.status,
      page,
      size,
    }),
    queryFn: () =>
      adminRealtyGroupReportsApi.listByGroup(groupPublicId!, {
        status: filters.status,
        page,
        size,
      }),
    enabled: !!groupPublicId,
    staleTime: 5_000,
  });
}

/**
 * Single report detail fetch. Drives the detail page +
 * resolve / dismiss modal.
 */
export function useGroupReportDetail(reportPublicId: string | undefined) {
  return useQuery({
    queryKey: realtyGroupReportsKeys.detail(reportPublicId ?? ""),
    queryFn: () => adminRealtyGroupReportsApi.detail(reportPublicId!),
    enabled: !!reportPublicId,
    staleTime: 5_000,
  });
}
