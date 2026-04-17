import type { AuctionStatus } from "@/types/auction";

/**
 * Terminal / non-terminal status helpers for the listing UI.
 *
 * The My Listings tab (Task 10) uses FILTER_GROUPS to collapse the 14
 * backend statuses into a handful of user-facing buckets (Active, Drafts,
 * Ended, Cancelled, Suspended).
 *
 * isEditable gates whether the Edit page accepts changes — only draft
 * statuses permit edits; everything else 409s on update from the backend.
 */
export const TERMINAL_STATUSES: AuctionStatus[] = [
  "ACTIVE",
  "CANCELLED",
  "SUSPENDED",
  "ENDED",
  "EXPIRED",
  "COMPLETED",
  "DISPUTED",
  "ESCROW_PENDING",
  "ESCROW_FUNDED",
  "TRANSFER_PENDING",
];

export const PRE_ACTIVE: AuctionStatus[] = [
  "DRAFT",
  "DRAFT_PAID",
  "VERIFICATION_PENDING",
  "VERIFICATION_FAILED",
];

export const EDITABLE: AuctionStatus[] = ["DRAFT", "DRAFT_PAID"];

export function isPreActive(s: AuctionStatus): boolean {
  return PRE_ACTIVE.includes(s);
}

export function isEditable(s: AuctionStatus): boolean {
  return EDITABLE.includes(s);
}

export function isTerminal(s: AuctionStatus): boolean {
  return TERMINAL_STATUSES.includes(s);
}

/**
 * User-facing filter buckets used by the My Listings tab. Order is the
 * intended tab-order in the UI.
 */
export const FILTER_GROUPS: Record<string, AuctionStatus[]> = {
  Active: ["ACTIVE"],
  Drafts: [
    "DRAFT",
    "DRAFT_PAID",
    "VERIFICATION_PENDING",
    "VERIFICATION_FAILED",
  ],
  Ended: [
    "ENDED",
    "ESCROW_PENDING",
    "ESCROW_FUNDED",
    "TRANSFER_PENDING",
    "COMPLETED",
    "EXPIRED",
  ],
  Cancelled: ["CANCELLED"],
  Suspended: ["SUSPENDED"],
};
