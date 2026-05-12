import { api } from "@/lib/api";
import type { GroupWallet } from "@/types/realty";

/**
 * Sub-project G §7.2 — admin wallet adjustment request.
 *
 * <p>{@code amount} is a signed L$ value: positive credits the group balance,
 * negative debits it. Zero is rejected by the backend with a 400 validation
 * error. The absolute value is also bounded by the configurable
 * {@code slpa.realty.admin-wallet-adjust-max-l} sanity ceiling; exceeding it
 * returns 422 with code {@code ADMIN_ADJUST_AMOUNT_OUT_OF_RANGE}.
 *
 * <p>{@code reason} is required, non-blank, and capped at 500 characters by
 * the backend.
 */
export interface AdminWalletAdjustRequest {
  amount: number;
  reason: string;
}

/**
 * POST /api/v1/admin/realty-groups/{publicId}/wallet/adjust.
 *
 * <p>Auth: JWT. Permission: admin role.
 *
 * <p>Returns the updated {@link GroupWallet} snapshot so the caller can
 * refresh the on-page balance card inline.
 *
 * <p>Error mapping (RFC 7807 problem details, surfaced via {@code ApiError}):
 * <ul>
 *   <li>422 {@code ADMIN_ADJUST_AMOUNT_OUT_OF_RANGE} — amount exceeds the
 *       configured sanity ceiling.</li>
 *   <li>422 {@code INSUFFICIENT_GROUP_BALANCE} — debit would push balance
 *       below zero.</li>
 *   <li>400 — validation (blank reason, zero amount, oversize reason).</li>
 *   <li>404 {@code REALTY_GROUP_NOT_FOUND} — group does not exist.</li>
 *   <li>403 — caller is not an admin.</li>
 * </ul>
 */
export function adjustGroupWallet(
  publicId: string,
  body: AdminWalletAdjustRequest,
): Promise<GroupWallet> {
  return api.post<GroupWallet>(
    `/api/v1/admin/realty-groups/${publicId}/wallet/adjust`,
    body,
  );
}

/**
 * GET /api/v1/admin/realty-groups/{publicId}/wallet.
 *
 * <p>Auth: JWT. Permission: admin role. Bypasses the leader-tier
 * {@code VIEW_GROUP_TRANSACTIONS} permission so an admin can see any group's
 * wallet snapshot without being a member.
 */
export function getAdminGroupWallet(publicId: string): Promise<GroupWallet> {
  return api.get<GroupWallet>(
    `/api/v1/admin/realty-groups/${publicId}/wallet`,
  );
}
