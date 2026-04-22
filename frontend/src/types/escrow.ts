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
 * The counterparty — seller sees the winner, winner sees the seller.
 * Fields mirror the backend's `CounterpartyDto` projection on the escrow
 * status response.
 */
export interface EscrowCounterparty {
  userId: number;
  displayName: string;
  slAvatarName: string;
  slAvatarUuid: string;
}

/**
 * Full response shape from `GET /api/v1/auctions/{id}/escrow`.
 */
export interface EscrowStatusResponse {
  escrowId: number;
  auctionId: number;
  parcelName: string;
  region: string;
  state: EscrowState;
  finalBidAmount: number;
  commissionAmt: number;
  payoutAmt: number;

  // Deadlines
  paymentDeadline: string;            // ISO-8601
  transferDeadline: string | null;    // null until funded

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

  counterparty: EscrowCounterparty;
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
  | "ESCROW_PAYOUT_STALLED";

export interface EscrowEnvelopeBase {
  type: EscrowEnvelopeType;
  auctionId: number;
  escrowId: number;
  state: EscrowState;
  serverTime: string;
}

export type EscrowEnvelope =
  | (EscrowEnvelopeBase & { type: "ESCROW_CREATED"; paymentDeadline: string })
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
    });
