"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminUserIps(id: number, enabled = true) {
  return useQuery({
    queryKey: adminQueryKeys.userIps(id),
    queryFn: () => adminApi.users.ips(id),
    enabled,
    staleTime: 30_000,
  });
}
