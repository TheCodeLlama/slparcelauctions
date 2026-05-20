import { api } from "@/lib/api";
import type {
  CouponGrantDto,
  ProspectiveDiscountsDto,
} from "@/types/coupon";

/**
 * Typed client for the user-facing coupon endpoints (see backend
 * `MeCouponController`). The `filter` param maps 1:1 to the controller's
 * `?filter=active|history` query string; defaults to `active`, matching
 * the server default.
 */
export function fetchMyCoupons(
  filter: "active" | "history" = "active",
): Promise<CouponGrantDto[]> {
  return api.get<CouponGrantDto[]>("/api/v1/me/coupons", {
    params: { filter },
  });
}

/**
 * Redeem a user-typed code. The backend returns 201 + the created
 * `CouponGrantDto` on success; any failure surfaces as an `ApiError`
 * whose `problem.code` is a `CouponRedemptionErrorCode`.
 */
export function redeemCoupon(code: string): Promise<CouponGrantDto> {
  return api.post<CouponGrantDto>("/api/v1/me/coupons/redeem", { code });
}

/**
 * Prospective listing-fee + commission rate the create-listing summary
 * card displays. The backend resolves the user's current best grants
 * for each target independently.
 */
export function fetchProspectiveDiscounts(): Promise<ProspectiveDiscountsDto> {
  return api.get<ProspectiveDiscountsDto>(
    "/api/v1/me/listings/prospective-discounts",
  );
}
