"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { revokeGrant } from "@/lib/api/coupons";
import type { CouponGrantDto } from "@/types/coupon";
import { adminCouponKey } from "./useAdminCoupon";

/**
 * Mutation hook for
 * `POST /api/v1/admin/coupons/{publicId}/grants/{grantPublicId}/revoke`.
 * On success invalidates the grants list (the row's state flips to
 * REVOKED) and the coupon detail (aggregate `activeGrants` decreases).
 */
export function useRevokeCouponGrant(couponPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (grantPublicId: string): Promise<CouponGrantDto> =>
      revokeGrant(couponPublicId, grantPublicId),
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: ["admin-coupon-grants", couponPublicId],
      });
      qc.invalidateQueries({ queryKey: adminCouponKey(couponPublicId) });
      qc.invalidateQueries({ queryKey: ["admin-coupons"] });
    },
  });
}
