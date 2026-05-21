"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { patchAdminCoupon } from "@/lib/api/coupons";
import type { CouponDto, PatchCouponRequest } from "@/types/coupon";
import { adminCouponKey } from "./useAdminCoupon";

/**
 * Mutation hook for `PATCH /api/v1/admin/coupons/{publicId}`. On
 * success invalidates the single-coupon detail key + the broader
 * admin-coupons list key so the list page picks up renamed
 * descriptions / status flips on the next visit.
 *
 * Service-level rejections (`IMMUTABLE_FIELD`,
 * `SIGNUP_WINDOW_PAIRED`) surface unchanged on `mutation.error` —
 * the Edit form picks the user-facing message off `ApiError.problem`.
 */
export function usePatchAdminCoupon(publicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: PatchCouponRequest): Promise<CouponDto> =>
      patchAdminCoupon(publicId, req),
    onSuccess: (coupon) => {
      // Seed the detail cache with the freshly-patched DTO so the
      // tabs see the change without an extra round-trip.
      qc.setQueryData(adminCouponKey(publicId), coupon);
      qc.invalidateQueries({ queryKey: adminCouponKey(publicId) });
      qc.invalidateQueries({ queryKey: ["admin-coupons"] });
    },
  });
}
