import { api } from "@/lib/api";
import type {
  AcceptTermsRequest,
  PayPenaltyRequest,
  PayPenaltyResponse,
  WalletView,
  WithdrawRequest,
  WithdrawResponse,
} from "@/types/wallet";

/**
 * Fetch the authenticated user's wallet view (balance, reserved, available,
 * penalty owed, terms-acceptance state, recent 50 ledger entries).
 */
export function getWallet(): Promise<WalletView> {
  return api.get<WalletView>("/api/v1/me/wallet");
}

/**
 * Site-initiated withdrawal. Recipient is always the user's verified SL
 * avatar — never client-supplied. Idempotent on `idempotencyKey`.
 */
export function withdraw(req: WithdrawRequest): Promise<WithdrawResponse> {
  return api.post<WithdrawResponse>("/api/v1/me/wallet/withdraw", req);
}

/**
 * Pay against the user's outstanding penalty. Partial payments allowed up
 * to the owed amount; gated on `available` (not `balance`) so reserved L$
 * for active bids isn't surrendered to penalty.
 */
export function payPenalty(req: PayPenaltyRequest): Promise<PayPenaltyResponse> {
  return api.post<PayPenaltyResponse>("/api/v1/me/wallet/pay-penalty", req);
}

/**
 * First-deposit click-through. Stamps `wallet_terms_accepted_at` and
 * `wallet_terms_version` on the user. Idempotent — re-accepting the same
 * version is a no-op server-side.
 */
export function acceptTerms(req: AcceptTermsRequest): Promise<void> {
  return api.post<void>("/api/v1/me/wallet/accept-terms", req);
}

/**
 * Pay listing fee from wallet for a DRAFT auction owned by the caller.
 * Transitions DRAFT -> DRAFT_PAID. Validates penalty == 0 and
 * available >= listing_fee_amount.
 */
export function payListingFee(
  auctionId: number | string,
  idempotencyKey: string,
): Promise<{ newBalance: number; newAvailable: number; auctionStatus: string }> {
  return api.post<{ newBalance: number; newAvailable: number; auctionStatus: string }>(
    `/api/v1/me/auctions/${auctionId}/pay-listing-fee`,
    { idempotencyKey },
  );
}
