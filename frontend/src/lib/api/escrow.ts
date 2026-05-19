import { api } from "@/lib/api";
import type {
  EscrowDisputeRequest,
  EscrowStatusResponse,
} from "@/types/escrow";

/**
 * Fetches the escrow status for an auction. Returns 404 if no escrow exists
 * (auction ended with no winner, or the auction isn't in the ENDED+SOLD
 * state yet). Returns 403 if the caller isn't the seller or the winner.
 */
export function getEscrowStatus(
  auctionId: number | string,
): Promise<EscrowStatusResponse> {
  return api.get<EscrowStatusResponse>(
    `/api/v1/auctions/${auctionId}/escrow`,
  );
}

/**
 * Files a dispute on an escrow. Source states must be ESCROW_PENDING,
 * FUNDED, or TRANSFER_PENDING — backend returns 409 ESCROW_INVALID_TRANSITION
 * for terminal states (COMPLETED / EXPIRED / DISPUTED / FROZEN).
 */
export function fileDispute(
  auctionId: number | string,
  body: EscrowDisputeRequest,
): Promise<EscrowStatusResponse> {
  return api.post<EscrowStatusResponse>(
    `/api/v1/auctions/${auctionId}/escrow/dispute`,
    body,
  );
}

/**
 * Triggers a manual "Set Sell To" verification (spec 2026-05-17 §9). The
 * backend responds 202 (the actual verify runs asynchronously via the
 * SL World API / bot) and returns the updated escrow status reflecting the
 * decremented attempt count. The eventual result lands on the page via the
 * `ESCROW_SELL_TO_SET` STOMP envelope → escrow-query invalidation.
 */
export function verifySellTo(
  auctionId: number | string,
): Promise<EscrowStatusResponse> {
  return api.post<EscrowStatusResponse>(
    `/api/v1/auctions/${auctionId}/escrow/verify-sell-to`,
  );
}

/**
 * Triggers a manual buy/transfer verification (spec 2026-05-17 §9; bot-dispatch
 * refactor 2026-05-18). Backend returns 202 — the actual check runs
 * asynchronously on the bot worker; the response carries the updated escrow
 * status with `manualVerifyPending=true`, and the eventual bot result lands on
 * the page via an escrow-status cache invalidation.
 */
export function verifyTransfer(
  auctionId: number | string,
): Promise<EscrowStatusResponse> {
  return api.post<EscrowStatusResponse>(
    `/api/v1/auctions/${auctionId}/escrow/verify-transfer`,
  );
}

/**
 * Requests human review of a stuck escrow (spec 2026-05-17 §9). The optional
 * `note` is forwarded to the admin queue. Backend returns 200 with the
 * updated escrow status (manualReviewStatus → OPEN).
 */
export function requestManualReview(
  auctionId: number | string,
  note?: string,
): Promise<EscrowStatusResponse> {
  return api.post<EscrowStatusResponse>(
    `/api/v1/auctions/${auctionId}/escrow/manual-review`,
    note ? { note } : undefined,
  );
}
