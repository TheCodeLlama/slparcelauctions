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
 * A listing in one of these states is archive-only. {@code DISPUTED} is a
 * resolvable mid-flight state in the post-rewire enum (admin can resume to
 * {@code TRANSFER_PENDING} or expire to {@code EXPIRED}) so it is NOT
 * terminal; {@code FROZEN} is the genuine fraud-hold sink.
 */
export const TERMINAL_STATUSES: AuctionStatus[] = [
  "CANCELLED",
  "SUSPENDED",
  "EXPIRED",
  "COMPLETED",
  "FROZEN",
];

/**
 * Post-ACTIVE statuses where the auction-detail page should render the
 * ended-view (winner / outcome panel) instead of the live bid form.
 * Covers every internal terminal plus the mid-flight settlement states
 * ({@code TRANSFER_PENDING}, {@code DISPUTED}) — bidding never resumes
 * from any of these. Pre-rewire this was just {@code "ENDED"}.
 *
 * Public viewers receive {@link PublicAuctionStatus} which still uses
 * {@code "ENDED"} to collapse all of these — callers consuming the union
 * DTO should use {@link isEndedView} which understands both.
 */
export const ENDED_VIEW_STATUSES: AuctionStatus[] = [
  "TRANSFER_PENDING",
  "DISPUTED",
  "COMPLETED",
  "EXPIRED",
  "FROZEN",
  "CANCELLED",
];

/**
 * Statuses at which the activate-page polling should stop — either
 * because activation succeeded (ACTIVE and downstream escrow/transfer
 * states indicate the wizard has handed off to the auction runtime) or
 * because the listing has reached a terminal failure.
 */
export const ACTIVATE_POLLING_STOP: AuctionStatus[] = [
  "ACTIVE",
  "CANCELLED",
  "SUSPENDED",
  "EXPIRED",
  "COMPLETED",
  "DISPUTED",
  "FROZEN",
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
 * True when the auction-detail page should show the ended-view (winner /
 * outcome panel) instead of the live bid form. Accepts either the public
 * collapsed status ({@code "ENDED"}) or any internal post-ACTIVE status
 * from {@link ENDED_VIEW_STATUSES}, so consumers narrowing on the
 * {@code PublicAuctionResponse | SellerAuctionResponse} union don't have
 * to enumerate both vocabularies at every call site.
 */
export function isEndedView(s: string): boolean {
  return s === "ENDED" || (ENDED_VIEW_STATUSES as string[]).includes(s);
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
    "TRANSFER_PENDING",
    "DISPUTED",
    "COMPLETED",
    "EXPIRED",
    "FROZEN",
  ],
  Cancelled: ["CANCELLED"],
  Suspended: ["SUSPENDED"],
};
