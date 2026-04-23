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
 *
 * Two related but distinct predicates live here:
 *   - isTerminal        — archive-only: no further backend transitions
 *                         possible, nothing left to observe.
 *   - isActivatePollingStop — the Activate page polling hook (Task 9)
 *                         stops polling once a listing reaches any of
 *                         these statuses. ACTIVE and the escrow/transfer
 *                         states are NOT terminal overall but ARE
 *                         "the activate page has done its job" signals:
 *                         once a listing is ACTIVE (or already beyond),
 *                         the activate-flow UI has no more work to do.
 */

/**
 * Truly terminal statuses — no further state transitions possible.
 * A listing in one of these states is archive-only.
 */
export const TERMINAL_STATUSES: AuctionStatus[] = [
  "CANCELLED",
  "SUSPENDED",
  "EXPIRED",
  "COMPLETED",
  "DISPUTED",
];

/**
 * Statuses at which the activate-page polling should stop — either
 * because activation succeeded (ACTIVE and downstream escrow/transfer
 * states indicate the wizard has handed off to the auction runtime) or
 * because the listing has reached a terminal failure. ENDED is included
 * because a completed auction cycle has no more activate-flow work.
 */
export const ACTIVATE_POLLING_STOP: AuctionStatus[] = [
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

export function isActivatePollingStop(s: AuctionStatus): boolean {
  return ACTIVATE_POLLING_STOP.includes(s);
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
