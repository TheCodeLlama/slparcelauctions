import { api } from "@/lib/api";
import type {
  BulkReinstateListingsRequest,
  BulkReinstateResult,
  BulkSuspendListingsRequest,
  BulkSuspendResult,
} from "@/types/realty";

/**
 * Realty Groups: F — Admin bulk listing suspend / reinstate API client.
 *
 * <p>Two action-style endpoints under
 * {@code /api/v1/admin/realty-groups/{publicId}/listings}:
 * <ul>
 *   <li>{@code POST /suspend-all} — cascades a force-suspend across every
 *       active listing on the group, optionally tied to a parent
 *       {@code RealtyGroupSuspension} row. Returns 200 with
 *       {@link BulkSuspendResult}.</li>
 *   <li>{@code POST /reinstate-all} — lifts every active bulk-cause
 *       suspension on the group. Returns 200 with {@link BulkReinstateResult}.</li>
 * </ul>
 *
 * <p>Both endpoints require {@code ROLE_ADMIN}.
 *
 * <p>Backend: {@code AdminRealtyGroupBulkListingsController}.
 */
export const realtyGroupBulkListingsApi = {
  suspendAll(
    groupPublicId: string,
    body: BulkSuspendListingsRequest,
  ): Promise<BulkSuspendResult> {
    return api.post<BulkSuspendResult>(
      `/api/v1/admin/realty-groups/${groupPublicId}/listings/suspend-all`,
      body,
    );
  },

  reinstateAll(
    groupPublicId: string,
    body: BulkReinstateListingsRequest,
  ): Promise<BulkReinstateResult> {
    return api.post<BulkReinstateResult>(
      `/api/v1/admin/realty-groups/${groupPublicId}/listings/reinstate-all`,
      body,
    );
  },
};
