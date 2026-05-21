"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { directGrantToUsers } from "@/lib/api/coupons";
import type { CouponGrantDto } from "@/types/coupon";
import { adminCouponKey } from "./useAdminCoupon";

/**
 * Mutation hook for `POST /api/v1/admin/coupons/{publicId}/grants`.
 * Invalidates the grants list for this coupon so the table reloads
 * with the newly-created grants. Also nudges the coupon detail key
 * since the aggregate `totalGrants` / `activeGrants` counters change.
 *
 * The returned array carries only newly-created grants; users already
 * at the `maxPerUser` ceiling are filtered server-side and silently
 * skipped. Callers can read `data?.length` to surface a "granted to N
 * users" toast.
 */
export function useDirectGrantCoupon(publicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (userPublicIds: string[]): Promise<CouponGrantDto[]> =>
      directGrantToUsers(publicId, userPublicIds),
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: ["admin-coupon-grants", publicId],
      });
      qc.invalidateQueries({ queryKey: adminCouponKey(publicId) });
      qc.invalidateQueries({ queryKey: ["admin-coupons"] });
    },
  });
}
