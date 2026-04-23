import { cn } from "@/lib/cn";
import type { AuctionStatus } from "@/types/auction";

/**
 * Compact status chip for the listing UI. Colours are mapped to M3 tokens
 * (never raw Tailwind palette classes so light/dark themes stay consistent).
 *
 * The MAP is typed as {@code Record<AuctionStatus, ...>}, so adding a
 * status to {@link AuctionStatus} without updating this file fails the
 * TypeScript build — this is the compile-time check Task 7 promises that
 * all 14 statuses are covered.
 */
const STATUS_MAP: Record<
  AuctionStatus,
  { label: string; cls: string }
> = {
  DRAFT: {
    label: "Draft",
    cls: "bg-surface-container-high text-on-surface-variant",
  },
  DRAFT_PAID: {
    label: "Paid",
    cls: "bg-secondary-container text-on-secondary-container",
  },
  VERIFICATION_PENDING: {
    label: "Verifying",
    cls: "bg-primary-container text-on-primary-container",
  },
  VERIFICATION_FAILED: {
    label: "Verify failed",
    cls: "bg-error-container text-on-error-container",
  },
  ACTIVE: {
    label: "Active",
    cls: "bg-tertiary-container text-on-tertiary-container",
  },
  ENDED: {
    label: "Ended",
    cls: "bg-surface-container-high text-on-surface-variant",
  },
  ESCROW_PENDING: {
    label: "Escrow pending",
    cls: "bg-secondary-container text-on-secondary-container",
  },
  ESCROW_FUNDED: {
    label: "Escrow funded",
    cls: "bg-secondary-container text-on-secondary-container",
  },
  TRANSFER_PENDING: {
    label: "Transferring",
    cls: "bg-secondary-container text-on-secondary-container",
  },
  COMPLETED: {
    label: "Completed",
    cls: "bg-tertiary-container text-on-tertiary-container",
  },
  CANCELLED: {
    label: "Cancelled",
    cls: "bg-surface-container-high text-on-surface-variant line-through",
  },
  EXPIRED: {
    label: "Expired",
    cls: "bg-surface-container-high text-on-surface-variant",
  },
  DISPUTED: {
    label: "Disputed",
    cls: "bg-error-container text-on-error-container",
  },
  SUSPENDED: {
    label: "Suspended",
    cls: "bg-error-container text-on-error-container",
  },
};

export function ListingStatusBadge({
  status,
  className,
}: {
  status: AuctionStatus;
  className?: string;
}) {
  const entry = STATUS_MAP[status];
  return (
    <span
      data-status={status}
      className={cn(
        "inline-flex items-center rounded-full px-2.5 py-0.5 text-label-sm font-medium",
        entry.cls,
        className,
      )}
    >
      {entry.label}
    </span>
  );
}
