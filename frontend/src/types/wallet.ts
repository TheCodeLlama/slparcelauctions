// frontend/src/types/wallet.ts

/**
 * Append-only ledger entry types. Mirror of the backend
 * `UserLedgerEntryType`. Direction (debit vs credit) is implicit in the
 * type — the entry's `amount` is always positive.
 *
 * `WITHDRAW_COMPLETED` and `WITHDRAW_REVERSED` exist on the backend for
 * audit but are filtered out of `/api/v1/me/wallet/ledger` responses —
 * the user-facing collapsed view returns one row per logical withdrawal
 * (the originating `WITHDRAW_QUEUED`) decorated with `withdrawalStatus`.
 * Keep them in the union so historical clients / tests don't break, but
 * production responses won't carry them.
 */
export type UserLedgerEntryType =
  | "DEPOSIT"
  | "WITHDRAW_QUEUED"
  | "WITHDRAW_COMPLETED"
  | "WITHDRAW_REVERSED"
  | "BID_RESERVED"
  | "BID_RELEASED"
  | "ESCROW_DEBIT"
  | "ESCROW_REFUND"
  | "LISTING_FEE_DEBIT"
  | "LISTING_FEE_REFUND"
  | "PENALTY_DEBIT"
  | "ADJUSTMENT";

/**
 * Status of a `WITHDRAW_QUEUED` row in the collapsed ledger view. Non-null
 * only on `WITHDRAW_QUEUED` entries; null for every other entry type.
 */
export type WithdrawalStatus = "PENDING" | "COMPLETED" | "REVERSED";

export interface LedgerEntry {
  publicId: string;
  entryType: UserLedgerEntryType;
  amount: number;
  balanceAfter: number;
  reservedAfter: number;
  refType: string | null;
  refId: number | null;
  description: string | null;
  createdAt: string;
  withdrawalStatus: WithdrawalStatus | null;
}

/**
 * Ledger filter envelope. Mirrors the query params accepted by
 * {@code GET /api/v1/me/wallet/ledger} (and the CSV export sibling).
 *
 * `from` / `to` are ISO-8601 instants — lower-bound inclusive, upper-bound
 * exclusive on the backend, so a UI that wants "today" should set
 * `to = start-of-tomorrow UTC`.
 *
 * `amountMin` / `amountMax` are bounds on the always-positive `amount`
 * column; direction is implicit in the entry type, not the sign.
 */
export interface LedgerFilter {
  entryTypes?: UserLedgerEntryType[];
  from?: string;
  to?: string;
  amountMin?: number;
  amountMax?: number;
}

export interface WalletView {
  balance: number;
  reserved: number;
  available: number;
  penaltyOwed: number;
  queuedForWithdrawal: number;
  termsAccepted: boolean;
  termsVersion: string | null;
  termsAcceptedAt: string | null;
  recentLedger: LedgerEntry[];
}

export interface WithdrawRequest {
  amount: number;
  idempotencyKey: string;
}

export interface WithdrawResponse {
  queuePublicId: string;
  newBalance: number;
  newAvailable: number;
  status: string;
}

export interface PayPenaltyRequest {
  amount: number;
  idempotencyKey: string;
}

export interface PayPenaltyResponse {
  newBalance: number;
  newAvailable: number;
  newPenaltyOwed: number;
}

export interface AcceptTermsRequest {
  termsVersion: string;
}
