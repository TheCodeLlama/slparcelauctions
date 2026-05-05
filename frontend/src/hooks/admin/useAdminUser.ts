"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminUser(publicId: string) {
  return useQuery({
    queryKey: adminQueryKeys.user(publicId),
    queryFn: () => adminApi.users.detail(publicId),
    staleTime: 10_000,
  });
}
