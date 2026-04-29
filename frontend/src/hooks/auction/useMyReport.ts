"use client";
import { useQuery } from "@tanstack/react-query";
import { userReportsApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useMyReport(auctionId: number | null) {
  return useQuery({
    queryKey: adminQueryKeys.myReport(auctionId ?? 0),
    queryFn: () => userReportsApi.myReport(auctionId!),
    enabled: auctionId != null,
    staleTime: 30_000,
  });
}
