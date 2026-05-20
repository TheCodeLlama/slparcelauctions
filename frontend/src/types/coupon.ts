// frontend/src/types/coupon.ts

/**
 * Coupon DTO types. Mirrors the backend
 * `com.slparcelauctions.backend.coupon.dto.*` records 1:1.
 *
 * Spec: docs/superpowers/specs/2026-05-20-coupon-codes-design.md.
 */

export type DiscountTarget = "LISTING_FEE" | "COMMISSION_RATE";

export type DiscountOp = "OVERRIDE" | "PERCENT_OFF" | "FLAT_OFF";

export type CouponGrantState = "ACTIVE" | "EXHAUSTED" | "EXPIRED" | "REVOKED";

export type CouponGrantSource =
  | "REDEMPTION"
  | "ADMIN_GRANT"
  | "SIGNUP_WINDOW";

/**
 * Discriminator carried on the `code` property of `ProblemDetail` by
 * `CouponExceptionHandler`. Frontend uses this to pick a user-facing
 * error message string for the redeem form.
 */
export type CouponRedemptionErrorCode =
  | "UNKNOWN_CODE"
  | "NOT_ELIGIBLE"
  | "ALREADY_REDEEMED"
  | "EXPIRED"
  | "PAUSED"
  | "MAX_REACHED"
  | "INACTIVE";

/**
 * One discount line on a coupon's bundle. `value` is a string because
 * the backend emits `BigDecimal` — preserving exact decimal precision
 * through the wire matters for commission rates (e.g. "0.025"). Render
 * sites parse with care.
 */
export interface CouponDiscountDto {
  target: DiscountTarget;
  op: DiscountOp;
  value: string;
  sortOrder?: number | null;
}

export interface CouponGrantDto {
  publicId: string;
  couponPublicId: string;
  code: string;
  grantedAt: string;
  expiresAt: string | null;
  remainingCount: number | null;
  state: CouponGrantState;
  source: CouponGrantSource;
  discounts: CouponDiscountDto[];
}

/**
 * Result of `GET /api/v1/me/listings/prospective-discounts`. Null
 * coupon attribution means "default rate/fee applies" for that target.
 */
export interface ProspectiveDiscountsDto {
  listingFeeLindens: number;
  commissionRate: string;
  listingFeeCouponPublicId: string | null;
  listingFeeCouponCode: string | null;
  commissionCouponPublicId: string | null;
  commissionCouponCode: string | null;
}
