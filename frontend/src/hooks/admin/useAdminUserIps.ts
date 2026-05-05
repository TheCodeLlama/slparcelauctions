"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminUserIps(publicId: string, enabled = true) {
  return useQuery({
    queryKey: adminQueryKeys.userIps(publicId),
    queryFn: () => adminApi.users.ips(publicId),
    enabled,
    staleTime: 30_000,
  });
}
