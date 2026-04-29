"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminUserCancellations(id: number, page: number, size = 25) {
  return useQuery({
    queryKey: adminQueryKeys.userTab(id, "cancellations", { page, size }),
    queryFn: () => adminApi.users.cancellations(id, page, size),
    staleTime: 10_000,
  });
}
