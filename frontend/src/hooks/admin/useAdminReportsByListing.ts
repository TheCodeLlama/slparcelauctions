"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminReportsByListing(auctionId: number | null) {
  return useQuery({
    queryKey: adminQueryKeys.reportListing(auctionId ?? 0),
    queryFn: () => adminApi.reports.byListing(auctionId!),
    enabled: auctionId != null,
    staleTime: 10_000,
  });
}
