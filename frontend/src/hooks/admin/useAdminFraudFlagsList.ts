"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import type { FraudFlagListStatus, FraudFlagReason } from "@/lib/admin/types";

export function useAdminFraudFlagsList(filters: {
  status: FraudFlagListStatus;
  reasons: FraudFlagReason[];
  page: number;
  size: number;
}) {
  return useQuery({
    queryKey: adminQueryKeys.fraudFlagsList(filters),
    queryFn: () => adminApi.fraudFlagsList(filters),
    staleTime: 10_000,
  });
}
