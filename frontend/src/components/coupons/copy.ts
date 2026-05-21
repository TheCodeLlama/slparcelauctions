import { isApiError } from "@/lib/api";
import type { CouponRedemptionErrorCode } from "@/types/coupon";

/**
 * User-facing copy for each {@link CouponRedemptionErrorCode} discriminator
 * the backend's `CouponExceptionHandler` puts on `ProblemDetail.code`.
 *
 * Shared by every redemption surface (wallet card, create-listing inline
 * expander) so they all phrase the same failure mode the same way.
 * Unknown codes fall back to a generic string in {@link errorMessageFor}.
 */
export const REDEMPTION_ERROR_COPY: Record<CouponRedemptionErrorCode, string> = {
  UNKNOWN_CODE: "We don't recognize that code.",
  NOT_ELIGIBLE: "This code isn't available for your account.",
  ALREADY_REDEEMED: "You've already redeemed this code.",
  EXPIRED: "This code has expired.",
  PAUSED: "This code is paused.",
  MAX_REACHED: "This code has been fully redeemed.",
  INACTIVE: "This code isn't active.",
};

/**
 * Resolve a thrown value from {@code useRedeemCoupon} into the right
 * inline error message. Picks the {@link REDEMPTION_ERROR_COPY} entry
 * for a known `CouponRedemptionErrorCode`, falls back to the backend
 * detail/title, and finally to a generic string.
 */
export function redeemErrorMessageFor(error: unknown): string {
  if (isApiError(error)) {
    const code = error.problem.code as CouponRedemptionErrorCode | undefined;
    if (code && code in REDEMPTION_ERROR_COPY) {
      return REDEMPTION_ERROR_COPY[code];
    }
    return error.problem.detail ?? error.problem.title ?? "Could not redeem code.";
  }
  if (error instanceof Error) return error.message;
  return "Could not redeem code.";
}
