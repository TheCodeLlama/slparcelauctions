import { api } from "@/lib/api";
import type {
  AdminCouponGrantsListParams,
  CouponDto,
  CouponGrantDto,
  CouponSummaryDto,
  CreateCouponRequest,
  DiscountTarget,
  PatchCouponRequest,
  ProspectiveDiscountsDto,
} from "@/types/coupon";
import type { Page } from "@/types/page";

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

/**
 * Admin coupon list params. `discount_target` is intentionally
 * snake_case to match the backend `@RequestParam(name = "discount_target")`
 * on `AdminCouponController#list`. `active` undefined means "no
 * filter" (both active and archived rows returned).
 */
export type AdminCouponsListParams = {
  q?: string;
  active?: boolean;
  discount_target?: DiscountTarget;
  page?: number;
  size?: number;
};

/**
 * Typed client for the admin coupon list endpoint
 * (`GET /api/v1/admin/coupons`). Returns the standard `Page<T>`
 * envelope from `PagedResponse.from(...)`.
 */
export function fetchAdminCoupons(
  params: AdminCouponsListParams = {},
): Promise<Page<CouponSummaryDto>> {
  return api.get<Page<CouponSummaryDto>>("/api/v1/admin/coupons", { params });
}

/**
 * Create a new coupon as admin. Mirrors `POST /api/v1/admin/coupons`
 * on the backend (`AdminCouponController#create`). Returns the full
 * `CouponDto` (admin shape) so the create form can redirect the user
 * to the new coupon's detail page using `publicId`.
 *
 * Service-level validation errors surface as `ApiError` with
 * `problem.code` set to one of `IMMUTABLE_FIELD` (duplicate code),
 * `LIFETIME_REQUIRED`, or `SIGNUP_WINDOW_PAIRED`. The form maps these
 * to a user-facing banner.
 */
export function createAdminCoupon(req: CreateCouponRequest): Promise<CouponDto> {
  return api.post<CouponDto>("/api/v1/admin/coupons", req);
}

/**
 * Fetch one coupon's full admin detail. Mirrors
 * `GET /api/v1/admin/coupons/{publicId}`. 404 surfaces as `ApiError`
 * with `problem.code = "UNKNOWN_CODE"` (mapped by
 * `CouponExceptionHandler`).
 */
export function fetchAdminCoupon(publicId: string): Promise<CouponDto> {
  return api.get<CouponDto>(`/api/v1/admin/coupons/${publicId}`);
}

/**
 * Patch a coupon. Mirrors `PATCH /api/v1/admin/coupons/{publicId}`.
 * Service-level rejections surface as `ApiError` with `problem.code`
 * set to `IMMUTABLE_FIELD` (lifetime / max-per-user lock after first
 * grant) or `SIGNUP_WINDOW_PAIRED`.
 */
export function patchAdminCoupon(
  publicId: string,
  req: PatchCouponRequest,
): Promise<CouponDto> {
  return api.patch<CouponDto>(`/api/v1/admin/coupons/${publicId}`, req);
}

/**
 * Archive (or hard-delete when no grants) a coupon. Mirrors
 * `DELETE /api/v1/admin/coupons/{publicId}`. Returns 204 / no body.
 */
export function deleteAdminCoupon(publicId: string): Promise<void> {
  return api.delete<void>(`/api/v1/admin/coupons/${publicId}`);
}

/**
 * Paged admin grant list for a single coupon. Mirrors
 * `GET /api/v1/admin/coupons/{publicId}/grants`. `state` and `source`
 * are passed straight through as enum names.
 */
export function fetchAdminCouponGrants(
  publicId: string,
  params: AdminCouponGrantsListParams = {},
): Promise<Page<CouponGrantDto>> {
  return api.get<Page<CouponGrantDto>>(
    `/api/v1/admin/coupons/${publicId}/grants`,
    { params },
  );
}

/**
 * Direct-grant the coupon to one or more users. Mirrors
 * `POST /api/v1/admin/coupons/{publicId}/grants`. Returns only the
 * newly-created grants; users already at the `maxPerUser` ceiling are
 * skipped silently.
 */
export function directGrantToUsers(
  publicId: string,
  userPublicIds: string[],
): Promise<CouponGrantDto[]> {
  return api.post<CouponGrantDto[]>(
    `/api/v1/admin/coupons/${publicId}/grants`,
    { userPublicIds },
  );
}

/**
 * Revoke a single grant. Mirrors
 * `POST /api/v1/admin/coupons/{publicId}/grants/{grantPublicId}/revoke`.
 * Returns the grant in its post-revoke shape (`state = REVOKED`).
 */
export function revokeGrant(
  publicId: string,
  grantPublicId: string,
): Promise<CouponGrantDto> {
  return api.post<CouponGrantDto>(
    `/api/v1/admin/coupons/${publicId}/grants/${grantPublicId}/revoke`,
    {},
  );
}
