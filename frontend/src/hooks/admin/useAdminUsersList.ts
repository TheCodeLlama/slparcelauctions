"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminUsersList(filters: { search?: string; page: number; size: number }) {
  return useQuery({
    queryKey: adminQueryKeys.usersList(filters),
    queryFn: () => adminApi.users.search(filters),
    staleTime: 10_000,
  });
}
