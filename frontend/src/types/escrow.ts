// frontend/src/types/escrow.ts

/**
 * Per-auction escrow lifecycle states. Mirror of the backend enum
 * `com.slparcelauctions.backend.escrow.EscrowState`. Terminal states are
 * COMPLETED, DISPUTED, EXPIRED, FROZEN (no transitions out in sub-spec 1).
 * FUNDED is a transient state — sub-spec 1 atomically advances it to
 * TRANSFER_PENDING within the same transaction, so external observers
 * rarely see it. Frontend treats FUNDED the same as TRANSFER_PENDING.
 */
export type EscrowState =
  | "ESCROW_PENDING"
  | "FUNDED"
  | "TRANSFER_PENDING"
  | "COMPLETED"
  | "DISPUTED"
  | "EXPIRED"
  | "FROZEN";

/** Categories surfaced in the dispute form + on the DISPUTED state card. */
export type EscrowDisputeReasonCategory =
  | "SELLER_NOT_RESPONSIVE"
  | "WRONG_PARCEL_TRANSFERRED"
  | "PAYMENT_NOT_CREDITED"
  | "FRAUD_SUSPECTED"
  | "OTHER";

/** Reason a `FROZEN` escrow entered that state. Backend-authoritative. */
export type EscrowFreezeReason =
  | "UNKNOWN_OWNER"
  | "PARCEL_DELETED"
  | "WORLD_API_PERSISTENT_FAILURE";

/**
 * Full response shape from `GET /api/v1/auctions/{publicId}/escrow`.
 */
export interface EscrowStatusResponse {
  escrowPublicId: string;
  auctionPublicId: string;
  /**
   * Winner's SL avatar name. Shown to the seller in the TRANSFER_PENDING
   * card so they can paste it into the SL viewer's About Land → Sell Land →
   * "Sell to" field. Null only for pre-FUNDED states (no winner resolved
   * yet) — should be present whenever an escrow row exists post-FUNDED.
   */
  winnerSlAvatarName: string | null;
  state: EscrowState;
  finalBidAmount: number;
  commissionAmt: number;
  payoutAmt: number;

  // Transfer deadline = fundedAt + 72h. Null until the escrow funds.
  // (paymentDeadline retired in spec 2026-05-16-wallet-only-escrow-funding:
  // escrows auto-fund inside createForEndedAuction so the pre-fund window
  // never persists.)
  transferDeadline: string | null;

  // Timestamps (nullable until the respective transition stamps them)
  fundedAt: string | null;
  transferConfirmedAt: string | null;
  completedAt: string | null;
  disputedAt: string | null;
  frozenAt: string | null;
  expiredAt: string | null;

  // Outcome context (nullable unless the state carries them)
  disputeReasonCategory: EscrowDisputeReasonCategory | null;
  disputeDescription: string | null;
  freezeReason: EscrowFreezeReason | null;

  // ── Transfer split (spec 2026-05-17-escrow-transfer-split-verification) ──
  // The TRANSFER_PENDING phase is split into "Set Sell To" (seller sets the
  // parcel's "Sell to:" field to the winner at L$0) then "Buy Parcel"
  // (winner buys the now-L$0 parcel). `sellToConfirmedAt` stamps when the
  // backend has verified the sell-to is correctly configured; until then the
  // escrow is in the Set-Sell-To sub-phase, after it the Buy-Parcel sub-phase.
  sellToConfirmedAt: string | null;
  /**
   * Last sell-to verification result code, e.g. `"SELL_TO_NOT_SET"`,
   * `"WRONG_BUYER"`, `"PRICE_NOT_ZERO"`. Null until a verify attempt has run.
   * Surfaced inline on the Set-Sell-To card so the seller knows why the last
   * check failed.
   */
  sellToLastResult: string | null;
  /** Manual sell-to verify attempts left (caps at 3, then the bot auto-checks every 30 min). */
  sellToVerifyAttemptsRemaining: number | null;
  /** Manual buy-parcel verify attempts left for the seller (caps at 3). */
  buyVerifySellerAttemptsRemaining: number | null;
  /** Manual buy-parcel verify attempts left for the winner (caps at 3). */
  buyVerifyBuyerAttemptsRemaining: number | null;
  /** Manual-review queue status for this escrow, null if no review was ever requested. */
  manualReviewStatus: "OPEN" | "RESOLVED" | "DISMISSED" | null;
  /** Which sub-phase a manual review was requested from, null if none. */
  manualReviewStep: "SET_SELL_TO" | "BUY_PARCEL" | null;
  /** SL map deep-link to the parcel (slurl), null until enrichment resolves it. */
  parcelMapUrl: string | null;
  /** SL viewer deep-link to the parcel (secondlife:// app URL), null until enrichment resolves it. */
  parcelViewerUrl: string | null;
  /**
   * True while a bot-dispatched VERIFY_SELL_TO or VERIFY_BUY_OWNER check is in
   * flight (set on Verify Sell To / Verify Purchase POST, cleared when the bot
   * result lands). The escrow page uses this to disable the verify button and
   * show "Verification process pending" copy — POST roundtrip success alone is
   * not enough to re-enable the button, since the bot may still be in transit.
   */
  manualVerifyPending: boolean;
}

/** Body shape for `POST /api/v1/auctions/{id}/escrow/dispute`. */
export interface EscrowDisputeRequest {
  reasonCategory: EscrowDisputeReasonCategory;
  description: string;
}

/**
 * WebSocket envelope types broadcast on `/topic/auction/{id}`. Per sub-spec 1
 * §8 these are coarse cache-invalidation signals — the frontend does NOT
 * read the variant-specific fields. They're typed here for forward
 * compatibility with Epic 09 notifications.
 */
export type EscrowEnvelopeType =
  | "ESCROW_CREATED"
  | "ESCROW_FUNDED"
  | "ESCROW_TRANSFER_CONFIRMED"
  | "ESCROW_COMPLETED"
  | "ESCROW_DISPUTED"
  | "ESCROW_EXPIRED"
  | "ESCROW_FROZEN"
  | "ESCROW_REFUND_COMPLETED"
  | "ESCROW_PAYOUT_STALLED"
  | "ESCROW_SELL_TO_SET";

export interface EscrowEnvelopeBase {
  type: EscrowEnvelopeType;
  auctionPublicId: string;
  escrowPublicId: string;
  state: EscrowState;
  serverTime: string;
}

export type EscrowEnvelope =
  | (EscrowEnvelopeBase & { type: "ESCROW_CREATED" })
  | (EscrowEnvelopeBase & { type: "ESCROW_FUNDED"; transferDeadline: string })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_TRANSFER_CONFIRMED";
      transferConfirmedAt: string;
    })
  | (EscrowEnvelopeBase & { type: "ESCROW_COMPLETED"; completedAt: string })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_DISPUTED";
      reasonCategory: EscrowDisputeReasonCategory;
    })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_EXPIRED";
      reason: "PAYMENT_TIMEOUT" | "TRANSFER_TIMEOUT";
    })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_FROZEN";
      reason: EscrowFreezeReason;
    })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_REFUND_COMPLETED";
      refundAmount: number;
    })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_PAYOUT_STALLED";
      attemptCount: number;
      lastError?: string;
    })
  | (EscrowEnvelopeBase & {
      type: "ESCROW_SELL_TO_SET";
      sellToConfirmedAt: string;
    });
