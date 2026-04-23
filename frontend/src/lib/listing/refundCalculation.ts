import type { AuctionStatus } from "@/types/auction";

/**
 * Refund model for the CancelListingModal (sub-spec 2 §8.2).
 *
 * The backend authoritative refund logic lives in CancellationService;
 * this frontend helper only mirrors the user-facing copy so the modal can
 * show the correct message before the seller clicks "Confirm cancel".
 *
 * Buckets:
 *   - DRAFT: no fee paid → no refund.
 *   - DRAFT_PAID / VERIFICATION_PENDING / VERIFICATION_FAILED: fee paid
 *     but listing never went live → full refund, processed within 24 h.
 *   - ACTIVE: cancelling an active listing forfeits the fee (per spec).
 *   - Anything else is not cancellable from the UI — the copy reflects
 *     that ("This listing cannot be cancelled.").
 */
export interface RefundInfo {
  kind: "NONE" | "FULL";
  amountLindens: number | null;
  copy: string;
}

export function computeRefund(
  status: AuctionStatus,
  listingFeeAmt: number | null,
): RefundInfo {
  switch (status) {
    case "DRAFT":
      return {
        kind: "NONE",
        amountLindens: null,
        copy: "No refund — no fee was paid yet.",
      };
    case "DRAFT_PAID":
    case "VERIFICATION_PENDING":
    case "VERIFICATION_FAILED": {
      const amount = listingFeeAmt ?? 0;
      return {
        kind: "FULL",
        amountLindens: amount,
        copy: `Refund: L$${amount} (full refund, processed within 24 hours).`,
      };
    }
    case "ACTIVE":
      return {
        kind: "NONE",
        amountLindens: null,
        copy:
          "No refund — cancelling an active listing does not refund the fee.",
      };
    default:
      return {
        kind: "NONE",
        amountLindens: null,
        copy: "This listing cannot be cancelled.",
      };
  }
}
