"use client";
import { useQuery } from "@tanstack/react-query";
import { userReportsApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useMyReport(auctionPublicId: string | null) {
  return useQuery({
    queryKey: adminQueryKeys.myReport(auctionPublicId ?? ""),
    queryFn: () => userReportsApi.myReport(auctionPublicId!),
    enabled: auctionPublicId != null,
    staleTime: 30_000,
  });
}
