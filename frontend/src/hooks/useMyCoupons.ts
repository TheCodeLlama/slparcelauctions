"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchMyCoupons } from "@/lib/api/coupons";
import type { CouponGrantDto } from "@/types/coupon";

/**
 * Query-key factory for the user's own coupon-grant list. Indexed by
 * the active/history filter so the WalletCouponsCard's two sections
 * share neither cache entry nor pagination state.
 *
 * Exposed so collaborators (e.g. `useRedeemCoupon`'s `onSuccess`) can
 * invalidate without re-typing the literal.
 */
export function myCouponsKey(
  filter: "active" | "history" = "active",
): readonly unknown[] {
  return ["me-coupons", filter] as const;
}

/**
 * Reads the authenticated user's coupon grants. The "active" filter
 * returns ACTIVE rows FIFO by `grantedAt`; "history" returns
 * non-ACTIVE rows most-recent-first.
 */
export function useMyCoupons(filter: "active" | "history" = "active") {
  return useQuery<CouponGrantDto[]>({
    queryKey: myCouponsKey(filter),
    queryFn: () => fetchMyCoupons(filter),
    staleTime: 30_000,
  });
}
