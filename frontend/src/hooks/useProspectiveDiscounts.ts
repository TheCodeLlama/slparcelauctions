"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchProspectiveDiscounts } from "@/lib/api/coupons";
import type { ProspectiveDiscountsDto } from "@/types/coupon";

/**
 * Shared query key for the `GET /api/v1/me/listings/prospective-discounts`
 * endpoint. Re-exported so collaborators (e.g. `useRedeemCoupon`'s
 * `onSuccess`) can invalidate without re-typing the literal.
 */
export const PROSPECTIVE_DISCOUNTS_KEY = ["prospective-discounts"] as const;

/**
 * Reads the listing-fee + commission rate the seller would pay *right now*
 * if they activated a listing, with any best-priced coupon grant
 * applied per target. The create-listing summary renders before/after
 * pricing from this DTO; if the user has no eligible grants the
 * `*CouponPublicId` / `*CouponCode` fields are null and the row falls
 * back to the platform default.
 */
export function useProspectiveDiscounts(enabled = true) {
  return useQuery<ProspectiveDiscountsDto>({
    queryKey: PROSPECTIVE_DISCOUNTS_KEY,
    queryFn: fetchProspectiveDiscounts,
    enabled,
    staleTime: 30_000,
  });
}
