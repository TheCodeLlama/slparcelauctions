"use client";
import { useQuery } from "@tanstack/react-query";
import {
  fetchAdminCouponGrants,
} from "@/lib/api/coupons";
import type { AdminCouponGrantsListParams } from "@/types/coupon";

/**
 * Query key for the per-coupon admin grant list. Exported so the
 * direct-grant and revoke mutations can invalidate the same cache key
 * shape without duplicating it.
 */
export const adminCouponGrantsKey = (
  publicId: string,
  params: AdminCouponGrantsListParams,
) => ["admin-coupon-grants", publicId, params] as const;

/**
 * Paged grant list for a single coupon. Filters (`state`, `source`)
 * map 1:1 to backend enum names. Used by the Grants tab on the admin
 * coupon detail page.
 */
export function useAdminCouponGrants(
  publicId: string,
  params: AdminCouponGrantsListParams = {},
) {
  return useQuery({
    queryKey: adminCouponGrantsKey(publicId, params),
    queryFn: () => fetchAdminCouponGrants(publicId, params),
    staleTime: 5_000,
  });
}
