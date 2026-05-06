import type { AuctionStatus } from "@/lib/admin/types";

/**
 * Maps an AuctionStatus to a Tailwind class pair for the status chip.
 * Centralized so the same color shows up in the table, the modal preview,
 * and any future per-status badge surface.
 */
export function statusBadgeClass(status: AuctionStatus): string {
  switch (status) {
    case "ACTIVE":
      return "bg-success-bg text-success";
    case "ENDED":
    case "COMPLETED":
      return "bg-info-bg text-info";
    case "ESCROW_PENDING":
    case "ESCROW_FUNDED":
    case "TRANSFER_PENDING":
      return "bg-info-bg text-info";
    case "DISPUTED":
      return "bg-warning-bg text-warning";
    case "SUSPENDED":
      return "bg-danger text-white";
    case "CANCELLED":
    case "EXPIRED":
      return "bg-bg-hover text-fg-muted";
    case "DRAFT":
    case "DRAFT_PAID":
    case "VERIFICATION_PENDING":
      return "bg-bg-hover text-fg-muted";
    case "VERIFICATION_FAILED":
      return "bg-warning-bg text-warning";
    default:
      return "bg-bg-hover text-fg-muted";
  }
}
