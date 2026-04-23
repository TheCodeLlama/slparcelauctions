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
