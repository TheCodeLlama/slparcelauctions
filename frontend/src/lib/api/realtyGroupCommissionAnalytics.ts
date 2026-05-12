import { api } from "@/lib/api";
import type { MemberCommissionRow } from "@/types/realty";

/**
 * Realty Groups: F — Leader commission analytics API client.
 *
 * <p>Single endpoint:
 * {@code GET /api/v1/realty-groups/{publicId}/analytics/commissions}.
 * Returns one row per current member of the group (including the leader)
 * with lifetime + last-30-day commission totals in L$. Permission gating
 * runs server-side — leaders and {@code MANAGE_MEMBERS} holders can read;
 * everyone else gets 403.
 *
 * <p>Backend: {@code RealtyGroupAnalyticsController.getCommissions}.
 */
export const realtyGroupCommissionAnalyticsApi = {
  get(groupPublicId: string): Promise<MemberCommissionRow[]> {
    return api.get<MemberCommissionRow[]>(
      `/api/v1/realty-groups/${groupPublicId}/analytics/commissions`,
    );
  },
};
