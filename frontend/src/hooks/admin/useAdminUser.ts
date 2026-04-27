"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminUser(id: number) {
  return useQuery({
    queryKey: adminQueryKeys.user(id),
    queryFn: () => adminApi.users.detail(id),
    staleTime: 10_000,
  });
}
