"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { createAdminCoupon } from "@/lib/api/coupons";
import type { CouponDto, CreateCouponRequest } from "@/types/coupon";

/**
 * Mutation hook for the admin create-coupon form. On success it
 * invalidates the cached `["admin-coupons", ...]` list pages so the
 * new row appears on the next visit, then routes to the new coupon's
 * detail page (`/admin/coupons/{publicId}`) where the user lands on
 * the Overview tab.
 *
 * Errors surface unchanged via `mutation.error` so the form can pick
 * the user-facing message from `ApiError.problem` (codes:
 * `IMMUTABLE_FIELD`, `LIFETIME_REQUIRED`, `SIGNUP_WINDOW_PAIRED`).
 */
export function useCreateAdminCoupon() {
  const router = useRouter();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: CreateCouponRequest): Promise<CouponDto> =>
      createAdminCoupon(req),
    onSuccess: (coupon) => {
      // Partial-key invalidation: every cached `["admin-coupons", {...}]`
      // page drops, regardless of its filter params.
      qc.invalidateQueries({ queryKey: ["admin-coupons"] });
      router.push(`/admin/coupons/${coupon.publicId}`);
    },
  });
}
