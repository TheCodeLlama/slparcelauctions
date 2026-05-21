"use client";
import { useQuery } from "@tanstack/react-query";
import {
  fetchAdminCoupons,
  type AdminCouponsListParams,
} from "@/lib/api/coupons";

/**
 * Query key for the admin coupons list. Exported so tests and
 * neighbouring mutations (Task 18 create form, Task 19 detail-page
 * patches) can invalidate the same cache entry without duplicating
 * the key shape.
 */
export const adminCouponsKey = (params: AdminCouponsListParams) =>
  ["admin-coupons", params] as const;

/**
 * Paged admin coupons list with the supplied filter params. Mirrors
 * the existing admin list hooks (`useAdminUsersList`,
 * `useAdminBansList`) in shape so the page component looks the same
 * as its neighbours.
 */
export function useAdminCoupons(params: AdminCouponsListParams) {
  return useQuery({
    queryKey: adminCouponsKey(params),
    queryFn: () => fetchAdminCoupons(params),
    staleTime: 10_000,
  });
}
