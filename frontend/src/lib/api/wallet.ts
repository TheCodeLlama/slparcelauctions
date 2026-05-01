import { api } from "@/lib/api";
import type { Page } from "@/types/page";
import type {
  AcceptTermsRequest,
  LedgerEntry,
  LedgerFilter,
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
 * Build the URLSearchParams for a {@link LedgerFilter}. Shared by
 * {@link getLedger} and {@link ledgerExportUrl} so a download link
 * always reflects the same filter chrome the JSON list was rendered for.
 */
function ledgerFilterParams(filter: LedgerFilter): URLSearchParams {
  const params = new URLSearchParams();
  if (filter.entryTypes?.length) {
    filter.entryTypes.forEach((t) => params.append("entryType", t));
  }
  if (filter.from) params.set("from", filter.from);
  if (filter.to) params.set("to", filter.to);
  if (filter.amountMin !== undefined) {
    params.set("amountMin", String(filter.amountMin));
  }
  if (filter.amountMax !== undefined) {
    params.set("amountMax", String(filter.amountMax));
  }
  return params;
}

/**
 * Fetch a page of the authenticated user's ledger. Server scopes by
 * JWT — caller never supplies userId. Sorted `created_at DESC` by the
 * backend regardless of filter combination.
 */
export function getLedger(
  filter: LedgerFilter,
  page: number,
  size: number,
): Promise<Page<LedgerEntry>> {
  const params = ledgerFilterParams(filter);
  params.set("page", String(page));
  params.set("size", String(size));
  return api.get<Page<LedgerEntry>>(
    `/api/v1/me/wallet/ledger?${params.toString()}`,
  );
}

/**
 * Build the URL for the streaming-CSV export sibling of {@link getLedger}.
 * Returned URL is a relative path with the same filter params as the JSON
 * call — the consumer triggers a browser download by clicking an anchor at
 * this href (no fetch, since the backend wires up the
 * {@code Content-Disposition: attachment} header).
 *
 * Backend rate-limits this endpoint to 1 request per 60s per user.
 */
export function ledgerExportUrl(filter: LedgerFilter): string {
  const params = ledgerFilterParams(filter);
  return `/api/v1/me/wallet/ledger/export.csv?${params.toString()}`;
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
