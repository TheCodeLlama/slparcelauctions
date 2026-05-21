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

/**
 * Admin list row. Mirrors `CouponSummaryDto` on the backend
 * (`coupon/dto/CouponSummaryDto.java`). `redeemableUntil` is an ISO
 * `OffsetDateTime`; `discounts` carries the full bundle so the table
 * can render a pill list without a per-row detail fetch. Grant
 * counters are pre-computed server-side.
 */
export interface CouponSummaryDto {
  publicId: string;
  code: string;
  description: string | null;
  active: boolean;
  redeemableUntil: string | null;
  discounts: CouponDiscountDto[];
  totalGrants: number;
  activeGrants: number;
  maxTotalRedemptions: number | null;
}

/**
 * Admin detail DTO. Mirrors `CouponDto` on the backend
 * (`coupon/dto/CouponDto.java`). `signupWindowStart` and
 * `signupWindowEnd` are ISO `LocalDate` strings (no time component);
 * `redeemableUntil`, `createdAt`, `updatedAt` are ISO `OffsetDateTime`s.
 * `allowedUserPublicIds` is the per-user allowlist when the coupon is
 * scoped to a specific set of users.
 */
export interface CouponDto {
  publicId: string;
  code: string;
  description: string | null;
  durationDays: number | null;
  useCount: number | null;
  redeemableUntil: string | null;
  maxTotalRedemptions: number | null;
  maxPerUser: number;
  signupWindowStart: string | null;
  signupWindowEnd: string | null;
  active: boolean;
  notifyOnGrant: boolean;
  discounts: CouponDiscountDto[];
  allowedUserPublicIds: string[];
  createdAt: string;
  updatedAt: string;
}

/**
 * Admin create-coupon payload. Mirrors `CreateCouponRequest` on the
 * backend (`coupon/dto/CreateCouponRequest.java`). All but `code` and
 * `discounts` are optional at the wire level; the service performs
 * additional cross-field validation (lifetime exactly-one,
 * signup-window paired, duplicate code).
 */
export interface CreateCouponRequest {
  code: string;
  description?: string;
  durationDays?: number;
  useCount?: number;
  redeemableUntil?: string;
  maxTotalRedemptions?: number;
  maxPerUser?: number;
  signupWindowStart?: string;
  signupWindowEnd?: string;
  active?: boolean;
  notifyOnGrant?: boolean;
  discounts: CouponDiscountDto[];
  allowedUserPublicIds?: string[];
}

/**
 * Admin patch-coupon payload. Mirrors `PatchCouponRequest` on the
 * backend (`coupon/dto/PatchCouponRequest.java`). All fields are
 * optional; only the keys actually present in the body are applied.
 *
 * Lifetime fields (`durationDays`, `useCount`), `maxPerUser`, and the
 * signup-window pair are locked at the service layer once any grant
 * exists for the coupon. The frontend disables their inputs as a
 * usability hint, but the server enforces the rule via
 * `IMMUTABLE_FIELD`.
 *
 * `null` on a nullable field clears the value; omitting the key
 * leaves the existing value untouched. `allowedUserPublicIds`
 * overwrites the entire allowlist when present.
 */
export interface PatchCouponRequest {
  description?: string | null;
  active?: boolean;
  notifyOnGrant?: boolean;
  redeemableUntil?: string | null;
  maxTotalRedemptions?: number | null;
  allowedUserPublicIds?: string[];
  durationDays?: number;
  useCount?: number;
  maxPerUser?: number;
}

/**
 * Body for `POST /api/v1/admin/coupons/{publicId}/grants`. The server
 * idempotently filters out users who already hold the coupon at the
 * `maxPerUser` ceiling and returns only the newly-created grants.
 */
export interface DirectGrantRequest {
  userPublicIds: string[];
}

/**
 * Filter params for the admin grant list endpoint
 * (`GET /api/v1/admin/coupons/{publicId}/grants`). `state` and `source`
 * map 1:1 to the backend enum names so the dropdown values can be
 * passed straight through.
 */
export type AdminCouponGrantsListParams = {
  state?: CouponGrantState;
  source?: CouponGrantSource;
  page?: number;
  size?: number;
};
