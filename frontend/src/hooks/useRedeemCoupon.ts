"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { redeemCoupon } from "@/lib/api/coupons";
import type { CouponGrantDto } from "@/types/coupon";

/**
 * Mutation wrapper around `POST /api/v1/me/coupons/redeem`. On success
 * invalidates both the user's grant list (so the newly-created ACTIVE
 * grant appears in WalletCouponsCard) and the prospective-discounts
 * query (so the create-listing summary reflects the new winner).
 *
 * On failure the caller reads `error.problem.code` (a
 * `CouponRedemptionErrorCode`) to render the right inline message.
 */
export function useRedeemCoupon() {
  const qc = useQueryClient();
  return useMutation<CouponGrantDto, Error, string>({
    mutationFn: (code: string) => redeemCoupon(code),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["me-coupons"] });
      qc.invalidateQueries({ queryKey: ["prospective-discounts"] });
    },
  });
}
