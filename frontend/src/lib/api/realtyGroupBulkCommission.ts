import { api } from "@/lib/api";
import type { BulkCommissionRatesRequest } from "@/types/realty";

/**
 * Realty Groups: F — Leader bulk commission-rate edit API client.
 *
 * <p>Single endpoint:
 * {@code PATCH /api/v1/realty-groups/{publicId}/members/commission-rates}.
 * The batch is atomic — any failed entry (member not in group, negative
 * rate, suspended group) rolls back the entire batch. Permission:
 * {@code MANAGE_MEMBERS} (leader holds implicitly).
 *
 * <p>Returns 204 No Content on success.
 *
 * <p>Backend: {@code RealtyGroupController.updateCommissionRates}.
 */
export const realtyGroupBulkCommissionApi = {
  update(
    groupPublicId: string,
    body: BulkCommissionRatesRequest,
  ): Promise<void> {
    return api.patch<void>(
      `/api/v1/realty-groups/${groupPublicId}/members/commission-rates`,
      body,
    );
  },
};
