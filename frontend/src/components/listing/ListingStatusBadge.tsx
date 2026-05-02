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
    cls: "bg-bg-hover text-fg-muted",
  },
  DRAFT_PAID: {
    label: "Paid",
    cls: "bg-info-bg text-info-flat",
  },
  VERIFICATION_PENDING: {
    label: "Verifying",
    cls: "bg-brand-soft text-brand",
  },
  VERIFICATION_FAILED: {
    label: "Verify failed",
    cls: "bg-danger-bg text-danger-flat",
  },
  ACTIVE: {
    label: "Active",
    cls: "bg-info-bg text-info-flat",
  },
  ENDED: {
    label: "Ended",
    cls: "bg-bg-hover text-fg-muted",
  },
  ESCROW_PENDING: {
    label: "Escrow pending",
    cls: "bg-info-bg text-info-flat",
  },
  ESCROW_FUNDED: {
    label: "Escrow funded",
    cls: "bg-info-bg text-info-flat",
  },
  TRANSFER_PENDING: {
    label: "Transferring",
    cls: "bg-info-bg text-info-flat",
  },
  COMPLETED: {
    label: "Completed",
    cls: "bg-info-bg text-info-flat",
  },
  CANCELLED: {
    label: "Cancelled",
    cls: "bg-bg-hover text-fg-muted line-through",
  },
  EXPIRED: {
    label: "Expired",
    cls: "bg-bg-hover text-fg-muted",
  },
  DISPUTED: {
    label: "Disputed",
    cls: "bg-danger-bg text-danger-flat",
  },
  SUSPENDED: {
    label: "Suspended",
    cls: "bg-danger-bg text-danger-flat",
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
        "inline-flex items-center rounded-full px-2.5 py-0.5 text-[11px] font-medium",
        entry.cls,
        className,
      )}
    >
      {entry.label}
    </span>
  );
}
