import { api } from "@/lib/api";
import type {
  AdminFraudFlagDetail,
  AdminFraudFlagSummary,
  AdminStatsResponse,
  FraudFlagListStatus,
  FraudFlagReason,
} from "./types";
import type { Page } from "@/types/page";

export const adminApi = {
  stats(): Promise<AdminStatsResponse> {
    return api.get("/api/v1/admin/stats");
  },

  fraudFlagsList(params: {
    status: FraudFlagListStatus;
    reasons: FraudFlagReason[];
    page: number;
    size: number;
  }): Promise<Page<AdminFraudFlagSummary>> {
    const search = new URLSearchParams();
    search.set("status", params.status);
    if (params.reasons.length > 0) search.set("reasons", params.reasons.join(","));
    search.set("page", String(params.page));
    search.set("size", String(params.size));
    return api.get(`/api/v1/admin/fraud-flags?${search.toString()}`);
  },

  fraudFlagDetail(flagId: number): Promise<AdminFraudFlagDetail> {
    return api.get(`/api/v1/admin/fraud-flags/${flagId}`);
  },

  dismissFraudFlag(flagId: number, adminNotes: string): Promise<AdminFraudFlagDetail> {
    return api.post(`/api/v1/admin/fraud-flags/${flagId}/dismiss`, { adminNotes });
  },

  reinstateFraudFlag(flagId: number, adminNotes: string): Promise<AdminFraudFlagDetail> {
    return api.post(`/api/v1/admin/fraud-flags/${flagId}/reinstate`, { adminNotes });
  },
};
