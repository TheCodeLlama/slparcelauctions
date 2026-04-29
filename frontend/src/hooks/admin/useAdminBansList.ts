"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import type { BanType } from "@/lib/admin/types";

export function useAdminBansList(filters: {
  status: "active" | "history";
  type?: BanType;
  page: number;
  size: number;
}) {
  return useQuery({
    queryKey: adminQueryKeys.bansList(filters),
    queryFn: () => adminApi.bans.list(filters),
    staleTime: 10_000,
  });
}
