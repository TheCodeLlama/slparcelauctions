// frontend/src/test/fixtures/escrow.ts
import type {
  EscrowEnvelope,
  EscrowEnvelopeType,
  EscrowStatusResponse,
} from "@/types/escrow";

export function fakeEscrow(
  overrides: Partial<EscrowStatusResponse> = {},
): EscrowStatusResponse {
  const base: EscrowStatusResponse = {
    escrowPublicId: "00000000-0000-0000-0000-000000000001",
    auctionPublicId: "00000000-0000-0000-0000-000000000007",
    state: "ESCROW_PENDING",
    finalBidAmount: 5000,
    commissionAmt: 250,
    payoutAmt: 4750,
    paymentDeadline: new Date(Date.now() + 48 * 3600 * 1000).toISOString(),
    transferDeadline: null,
    fundedAt: null,
    transferConfirmedAt: null,
    completedAt: null,
    disputedAt: null,
    frozenAt: null,
    expiredAt: null,
    disputeReasonCategory: null,
    disputeDescription: null,
    freezeReason: null,
  };
  return { ...base, ...overrides };
}

export function fakeEscrowEnvelope<T extends EscrowEnvelopeType>(
  type: T,
  overrides: Partial<EscrowEnvelope> = {},
): EscrowEnvelope {
  const baseCommon = {
    auctionPublicId: "00000000-0000-0000-0000-000000000007",
    escrowPublicId: "00000000-0000-0000-0000-000000000001",
    serverTime: new Date().toISOString(),
  };

  switch (type) {
    case "ESCROW_CREATED":
      return {
        type: "ESCROW_CREATED",
        state: "ESCROW_PENDING",
        paymentDeadline: new Date(Date.now() + 48 * 3600 * 1000).toISOString(),
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_FUNDED":
      return {
        type: "ESCROW_FUNDED",
        state: "TRANSFER_PENDING",
        transferDeadline: new Date(Date.now() + 72 * 3600 * 1000).toISOString(),
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_TRANSFER_CONFIRMED":
      return {
        type: "ESCROW_TRANSFER_CONFIRMED",
        state: "TRANSFER_PENDING",
        transferConfirmedAt: new Date().toISOString(),
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_COMPLETED":
      return {
        type: "ESCROW_COMPLETED",
        state: "COMPLETED",
        completedAt: new Date().toISOString(),
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_DISPUTED":
      return {
        type: "ESCROW_DISPUTED",
        state: "DISPUTED",
        reasonCategory: "OTHER",
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_EXPIRED":
      return {
        type: "ESCROW_EXPIRED",
        state: "EXPIRED",
        reason: "PAYMENT_TIMEOUT",
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_FROZEN":
      return {
        type: "ESCROW_FROZEN",
        state: "FROZEN",
        reason: "UNKNOWN_OWNER",
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_REFUND_COMPLETED":
      return {
        type: "ESCROW_REFUND_COMPLETED",
        state: "EXPIRED",
        refundAmount: 5000,
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    case "ESCROW_PAYOUT_STALLED":
      return {
        type: "ESCROW_PAYOUT_STALLED",
        state: "TRANSFER_PENDING",
        attemptCount: 4,
        lastError: "terminal offline",
        ...baseCommon,
        ...overrides,
      } as EscrowEnvelope;
    default: {
      const _exhaustive: never = type;
      throw new Error(`Unhandled envelope type: ${_exhaustive}`);
    }
  }
}
