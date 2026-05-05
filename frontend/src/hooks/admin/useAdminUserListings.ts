"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminUserListings(publicId: string, page: number, size = 25) {
  return useQuery({
    queryKey: adminQueryKeys.userTab(publicId, "listings", { page, size }),
    queryFn: () => adminApi.users.listings(publicId, page, size),
    staleTime: 10_000,
  });
}
