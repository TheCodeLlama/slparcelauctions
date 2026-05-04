// Wire-shape mirrors of the cancellation-penalty DTOs (Epic 08 sub-spec 2 §7).
// Source of truth on the Java side:
//   - CancellationOffenseKind enum
//     (com.slparcelauctions.backend.auction.CancellationOffenseKind)
//   - SuspensionReason enum (used as the {@code code} field on the structured
//     ProblemDetail emitted by SellerSuspendedException — frontend names it
//     {@code SuspensionReasonCode} to make the routing intent explicit)
//   - CancellationStatusResponse + nested CurrentSuspension + NextConsequenceDto
//     (com.slparcelauctions.backend.auction.dto.*)
//   - CancellationHistoryDto + nested PenaltyApplied
//
// Kept flat and un-opinionated so the lib/api layer can JSON.parse straight
// into these types without custom mapping. If a backend record adds a field,
// add it here too — there is no DTO codegen.

/**
 * Five-rung ladder applied at cancel-with-bids time. Mirrors the Java enum.
 *
 * <ul>
 *   <li>{@code NONE} — pre-active or active-without-bids cancellation; no
 *       penalty was logged. {@code CancellationLog.penaltyKind} is
 *       {@code null} or {@code NONE} in this case.</li>
 *   <li>{@code WARNING} — first cancel-with-bids; logged but no L$ debit and
 *       no suspension.</li>
 *   <li>{@code PENALTY} — second cancel-with-bids; L$ debit + listing
 *       suspension until paid.</li>
 *   <li>{@code PENALTY_AND_30D} — third cancel-with-bids; L$ debit + 30-day
 *       suspension that runs concurrently with the debt.</li>
 *   <li>{@code PERMANENT_BAN} — fourth cancel-with-bids; sets
 *       {@code User.bannedFromListing = true}.</li>
 * </ul>
 */
export type CancellationOffenseKind =
  | "NONE"
  | "WARNING"
  | "PENALTY"
  | "PENALTY_AND_30D"
  | "PERMANENT_BAN";

/**
 * The {@code code} field on the structured ProblemDetail emitted by
 * {@code SellerSuspendedException}. The listing wizard's submit handler
 * branches on this value to render focused error copy.
 */
export type SuspensionReasonCode =
  | "PENALTY_OWED"
  | "TIMED_SUSPENSION"
  | "PERMANENT_BAN";

/**
 * Echo of the three new {@code User} columns. The same fields are also
 * surfaced on {@link CurrentUser} so the dashboard banner can render off a
 * single {@code /me} fetch — this nested object lets the cancel modal read
 * them out of the {@code /me/cancellation-status} response without a second
 * round-trip when {@code /me} is stale.
 */
export interface CurrentSuspension {
  penaltyBalanceOwed: number;
  listingSuspensionUntil: string | null;
  bannedFromListing: boolean;
}

/**
 * Cancel-modal preview row — what the next cancel-with-bids would trigger
 * given {@code priorOffensesWithBids}. {@code kind} is never {@code NONE} —
 * the question is hypothetical so even a clean record yields
 * {@code WARNING}. {@code suspends30Days} / {@code permanentBan} are
 * derived from {@code kind} server-side so the frontend can map kind →
 * badge in one place.
 */
export interface NextConsequence {
  kind: CancellationOffenseKind;
  amountL: number | null;
  suspends30Days: boolean;
  permanentBan: boolean;
}

/**
 * Response body for {@code GET /api/v1/users/me/cancellation-status}.
 * Used by the cancel modal to drive the consequence-aware copy table and
 * by tests that exercise the four banner variants.
 */
export interface CancellationStatusResponse {
  priorOffensesWithBids: number;
  currentSuspension: CurrentSuspension;
  nextConsequenceIfBidsPresent: NextConsequence;
}

/**
 * Snapshotted penalty payload on a single history row. Null when the log's
 * {@code penaltyKind} was {@code NONE} (pre-active / active-without-bids
 * cancellation) — the dashboard renders a "No penalty" badge in that case.
 */
export interface CancellationHistoryPenalty {
  kind: CancellationOffenseKind;
  amountL: number | null;
}

/**
 * Single row in {@code GET /api/v1/users/me/cancellation-history}. All
 * fields are read straight off the snapshotted log row server-side — the
 * ladder is not re-derived against today's offense count, so historical
 * rows reflect what the seller saw at the time of cancellation.
 */
export interface CancellationHistoryDto {
  auctionPublicId: string;
  auctionTitle: string;
  primaryPhotoUrl: string | null;
  cancelledFromStatus: string;
  hadBids: boolean;
  reason: string | null;
  cancelledAt: string;
  penaltyApplied: CancellationHistoryPenalty | null;
}
