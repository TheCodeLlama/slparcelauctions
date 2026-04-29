"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminFraudFlag(flagId: number | null) {
  return useQuery({
    queryKey: flagId ? adminQueryKeys.fraudFlagDetail(flagId) : ["admin-flag-noop"],
    queryFn: () => adminApi.fraudFlagDetail(flagId!),
    enabled: flagId !== null,
    staleTime: 10_000,
  });
}
