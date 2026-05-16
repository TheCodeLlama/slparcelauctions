import type { AuctionStatus } from "@/types/auction";

/**
 * Refund model for the CancelListingModal.
 *
 * The backend authoritative refund logic lives in CancellationService +
 * ListingFeeRefundProcessorTask; this frontend helper only mirrors the
 * user-facing copy so the modal can show the correct message before the
 * seller clicks "Confirm cancel".
 *
 * Buckets:
 *   - DRAFT: no fee paid → no refund.
 *   - DRAFT_PAID / VERIFICATION_PENDING / VERIFICATION_FAILED: fee paid
 *     but listing never went live → full refund, credited instantly to
 *     the SLParcels wallet that paid (group wallet for case-3 listings,
 *     seller's user wallet otherwise).
 *   - ACTIVE: cancelling an active listing forfeits the fee.
 *   - Anything else is not cancellable from the UI.
 */
export interface RefundInfo {
  kind: "NONE" | "FULL";
  amountLindens: number | null;
  copy: string;
}

/**
 * @param status         current auction status
 * @param listingFeeAmt  fee paid, in L$
 * @param isGroupListing true when the auction is case-3 (the fee came
 *                       from a realty group's wallet); drives whether
 *                       the copy says "your wallet" or "the group's
 *                       wallet"
 */
export function computeRefund(
  status: AuctionStatus,
  listingFeeAmt: number | null,
  isGroupListing: boolean = false,
): RefundInfo {
  switch (status) {
    case "DRAFT":
      return {
        kind: "NONE",
        amountLindens: null,
        copy: "No refund. No fee was paid yet.",
      };
    case "DRAFT_PAID":
    case "VERIFICATION_PENDING":
    case "VERIFICATION_FAILED": {
      const amount = listingFeeAmt ?? 0;
      const target = isGroupListing
        ? "the group's SLParcels wallet"
        : "your SLParcels wallet";
      return {
        kind: "FULL",
        amountLindens: amount,
        copy: `Refund: L$${amount}, credited instantly to ${target}.`,
      };
    }
    case "ACTIVE":
      return {
        kind: "NONE",
        amountLindens: null,
        copy:
          "No refund. Cancelling an active listing does not refund the fee.",
      };
    default:
      return {
        kind: "NONE",
        amountLindens: null,
        copy: "This listing cannot be cancelled.",
      };
  }
}
