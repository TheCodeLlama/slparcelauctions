// Admin escrow manual-review queue types (spec §7). Mirrors the backend
// records in `com.slparcelauctions.backend.admin.escrowreview` 1:1 — public
// ids only on the wire (UUID `publicId` per the BaseEntity convention), the
// parcel name + SLURL for at-a-glance triage, and the observed bot /
// world-API evidence an admin needs to pick a resolution.

import type { EscrowState } from "./disputes";

export type ManualReviewStep = "SET_SELL_TO" | "BUY_PARCEL";

export type ManualReviewReason =
  | "USER_REQUESTED"
  | "BOT_PERSISTENT_FAILURE"
  | "WORLD_API_PERSISTENT_FAILURE";

export type ManualReviewStatus = "OPEN" | "RESOLVED" | "DISMISSED";

export type ManualReviewRole = "SELLER" | "BUYER" | "SYSTEM";

export type ManualReviewResolution =
  | "FORCE_CONFIRM_SELL_TO"
  | "FORCE_COMPLETE_TRANSFER"
  | "REFUND_WINNER"
  | "DISMISS";

// Mirrors AdminEscrowReviewRow.java
export type AdminEscrowReviewRow = {
  reviewPublicId: string;
  escrowPublicId: string;
  auctionPublicId: string;
  parcelName: string;
  step: ManualReviewStep;
  reason: ManualReviewReason;
  status: ManualReviewStatus;
  requestedRole: ManualReviewRole;
  createdAt: string;
  ageMinutes: number;
};

// Mirrors AdminEscrowReviewDetail.java
export type AdminEscrowReviewDetail = {
  reviewPublicId: string;
  escrowPublicId: string;
  auctionPublicId: string;
  auctionTitle: string;
  parcelName: string;
  slurl: string;
  // Review row
  step: ManualReviewStep;
  reason: ManualReviewReason;
  status: ManualReviewStatus;
  requestedRole: ManualReviewRole;
  resolution: ManualReviewResolution | null;
  adminNotes: string | null;
  createdAt: string;
  resolvedAt: string | null;
  // Escrow snapshot + observed evidence
  escrowState: EscrowState;
  finalBidAmount: number;
  fundedAt: string | null;
  sellToConfirmedAt: string | null;
  transferConfirmedAt: string | null;
  transferDeadline: string | null;
  sellToLastResult: string | null;
  sellToLastCheckedAt: string | null;
  sellToVerifyAttempts: number;
  buyVerifySellerAttempts: number;
  buyVerifyBuyerAttempts: number;
  consecutiveSellToBotFailures: number;
  consecutiveWorldApiFailures: number;
};

// Mirrors AdminEscrowReviewResolveRequest.java (action = ManualReviewResolution,
// adminNote 1..500 enforced server-side; the panel mirrors the constraint).
export type AdminEscrowReviewResolveRequest = {
  action: ManualReviewResolution;
  adminNote: string;
};

// Mirrors AdminEscrowReviewResolveResponse.java
export type AdminEscrowReviewResolveResponse = {
  reviewPublicId: string;
  newStatus: ManualReviewStatus;
  resolution: ManualReviewResolution;
  resolvedAt: string;
};

export type AdminEscrowReviewFilters = {
  status?: ManualReviewStatus;
  page?: number;
  size?: number;
};
