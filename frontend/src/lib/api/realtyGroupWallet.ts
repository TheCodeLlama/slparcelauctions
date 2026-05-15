import { api } from "@/lib/api";
import type {
  GroupDepositRequest,
  GroupDepositResponse,
  GroupLedgerEntry,
  GroupWallet,
  GroupWithdrawRequest,
  GroupWithdrawResponse,
} from "@/types/realty";

/**
 * Fetch the wallet view (balance, reserved, available, 50-entry recent
 * ledger) for the given group.
 *
 * Backend: {@code GET /api/v1/realty/groups/{publicId}/wallet}
 * Auth: JWT. Permission: leader OR {@code VIEW_GROUP_TRANSACTIONS}.
 */
export function getGroupWallet(publicId: string): Promise<GroupWallet> {
  return api.get<GroupWallet>(`/api/v1/realty/groups/${publicId}/wallet`);
}

/**
 * Fetch a cursor-paginated page of the group's ledger. Sorted
 * {@code created_at DESC} by the backend.
 *
 * Backend: {@code GET /api/v1/realty/groups/{publicId}/wallet/ledger}
 * Auth: JWT. Permission: leader OR {@code VIEW_GROUP_TRANSACTIONS}.
 *
 * @param cursor ISO-8601 {@code createdAt} of the oldest entry from the
 *   previous page. Omit for the first page.
 * @param limit  Max entries to return (backend clamps to 100; defaults to 50).
 */
export function getGroupLedger(
  publicId: string,
  cursor?: string,
  limit?: number,
): Promise<GroupLedgerEntry[]> {
  return api.get<GroupLedgerEntry[]>(
    `/api/v1/realty/groups/${publicId}/wallet/ledger`,
    {
      params: {
        ...(cursor !== undefined ? { cursor } : {}),
        ...(limit !== undefined ? { limit } : {}),
      },
    },
  );
}

/**
 * Initiate a withdrawal from the group wallet to the group leader's
 * verified SL avatar. Idempotent on {@code idempotencyKey}.
 *
 * Backend: {@code POST /api/v1/realty/groups/{publicId}/wallet/withdraw}
 * Auth: JWT. Permission: leader OR {@code WITHDRAW_FROM_GROUP_WALLET}.
 *
 * On failure the backend returns RFC 7807 problem details — the
 * {@link ApiError} from {@code @/lib/api} surfaces them via
 * {@code err.problem} (e.g. {@code code: "INSUFFICIENT_GROUP_BALANCE"},
 * {@code available: number}, {@code requested: number}).
 */
export function withdrawFromGroupWallet(
  publicId: string,
  body: GroupWithdrawRequest,
): Promise<GroupWithdrawResponse> {
  return api.post<GroupWithdrawResponse>(
    `/api/v1/realty/groups/${publicId}/wallet/withdraw`,
    body,
  );
}

/**
 * Deposit L$ from the caller's personal SLParcels wallet into the
 * group's wallet. Atomic; idempotent on {@code idempotencyKey}.
 *
 * Backend: {@code POST /api/v1/realty/groups/{publicId}/wallet/deposit}
 * Auth: JWT. Permission: leader OR {@code DEPOSIT_TO_GROUP_WALLET}.
 */
export function depositToGroupWallet(
  publicId: string,
  body: GroupDepositRequest,
): Promise<GroupDepositResponse> {
  return api.post<GroupDepositResponse>(
    `/api/v1/realty/groups/${publicId}/wallet/deposit`,
    body,
  );
}
