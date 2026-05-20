"use client";
import { useQuery } from "@tanstack/react-query";
import { fetchAdminCoupon } from "@/lib/api/coupons";

/**
 * Query key for a single admin coupon's detail fetch. Exported so
 * mutation hooks (`usePatchAdminCoupon`, `useDeleteAdminCoupon`) can
 * invalidate the same cache entry without duplicating the shape.
 */
export const adminCouponKey = (publicId: string) =>
  ["admin-coupon", publicId] as const;

/**
 * Fetches `GET /api/v1/admin/coupons/{publicId}`. Used by the detail
 * page wrapper so the Overview, Grants, and Edit tabs all observe the
 * same `CouponDto` without each re-fetching.
 */
export function useAdminCoupon(publicId: string) {
  return useQuery({
    queryKey: adminCouponKey(publicId),
    queryFn: () => fetchAdminCoupon(publicId),
    staleTime: 10_000,
  });
}
