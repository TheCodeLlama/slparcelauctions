"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminReportsList(filters: {
  status: "open" | "reviewed" | "all";
  page: number;
  size: number;
}) {
  return useQuery({
    queryKey: adminQueryKeys.reportsList(filters),
    queryFn: () => adminApi.reports.list(filters),
    staleTime: 10_000,
  });
}
