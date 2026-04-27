import { api } from "@/lib/api";
import type {
  AdminBanRow,
  AdminFraudFlagDetail,
  AdminFraudFlagSummary,
  AdminReportDetail,
  AdminReportListingRow,
  AdminStatsResponse,
  AdminUserBidRow,
  AdminUserCancellationRow,
  AdminUserDetail,
  AdminUserFraudFlagRow,
  AdminUserListingRow,
  AdminUserModerationRow,
  AdminUserReportRow,
  AdminUserSummary,
  AdminActionTargetType,
  BanType,
  CreateBanRequest,
  FraudFlagListStatus,
  FraudFlagReason,
  MyReportResponse,
  ReportRequest,
  UserIpProjection,
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

  reports: {
    list(params: { status: "open" | "reviewed" | "all"; page: number; size: number }):
        Promise<Page<AdminReportListingRow>> {
      const search = new URLSearchParams({
        status: params.status,
        page: String(params.page),
        size: String(params.size),
      });
      return api.get(`/api/v1/admin/reports?${search.toString()}`);
    },
    byListing(auctionId: number): Promise<AdminReportDetail[]> {
      return api.get(`/api/v1/admin/reports/listing/${auctionId}`);
    },
    detail(id: number): Promise<AdminReportDetail> {
      return api.get(`/api/v1/admin/reports/${id}`);
    },
    dismiss(id: number, notes: string): Promise<AdminReportDetail> {
      return api.post(`/api/v1/admin/reports/${id}/dismiss`, { notes });
    },
    warnSeller(auctionId: number, notes: string): Promise<void> {
      return api.post(`/api/v1/admin/reports/listing/${auctionId}/warn-seller`, { notes });
    },
    suspendListing(auctionId: number, notes: string): Promise<void> {
      return api.post(`/api/v1/admin/reports/listing/${auctionId}/suspend`, { notes });
    },
    cancelListing(auctionId: number, notes: string): Promise<void> {
      return api.post(`/api/v1/admin/reports/listing/${auctionId}/cancel`, { notes });
    },
  },

  bans: {
    list(params: { status: "active" | "history"; type?: BanType; page: number; size: number }):
        Promise<Page<AdminBanRow>> {
      const search = new URLSearchParams({
        status: params.status,
        page: String(params.page),
        size: String(params.size),
      });
      if (params.type) search.set("type", params.type);
      return api.get(`/api/v1/admin/bans?${search.toString()}`);
    },
    create(body: CreateBanRequest): Promise<AdminBanRow> {
      return api.post(`/api/v1/admin/bans`, body);
    },
    lift(id: number, liftedReason: string): Promise<AdminBanRow> {
      return api.post(`/api/v1/admin/bans/${id}/lift`, { liftedReason });
    },
  },

  users: {
    search(params: { search?: string; page: number; size: number }):
        Promise<Page<AdminUserSummary>> {
      const sp = new URLSearchParams({ page: String(params.page), size: String(params.size) });
      if (params.search) sp.set("search", params.search);
      return api.get(`/api/v1/admin/users?${sp.toString()}`);
    },
    detail(id: number): Promise<AdminUserDetail> {
      return api.get(`/api/v1/admin/users/${id}`);
    },
    listings(id: number, page: number, size: number): Promise<Page<AdminUserListingRow>> {
      return api.get(`/api/v1/admin/users/${id}/listings?page=${page}&size=${size}`);
    },
    bids(id: number, page: number, size: number): Promise<Page<AdminUserBidRow>> {
      return api.get(`/api/v1/admin/users/${id}/bids?page=${page}&size=${size}`);
    },
    cancellations(id: number, page: number, size: number): Promise<Page<AdminUserCancellationRow>> {
      return api.get(`/api/v1/admin/users/${id}/cancellations?page=${page}&size=${size}`);
    },
    reports(id: number, page: number, size: number): Promise<Page<AdminUserReportRow>> {
      return api.get(`/api/v1/admin/users/${id}/reports?page=${page}&size=${size}`);
    },
    fraudFlags(id: number, page: number, size: number): Promise<Page<AdminUserFraudFlagRow>> {
      return api.get(`/api/v1/admin/users/${id}/fraud-flags?page=${page}&size=${size}`);
    },
    moderation(id: number, page: number, size: number): Promise<Page<AdminUserModerationRow>> {
      return api.get(`/api/v1/admin/users/${id}/moderation?page=${page}&size=${size}`);
    },
    ips(id: number): Promise<UserIpProjection[]> {
      return api.get(`/api/v1/admin/users/${id}/ips`);
    },
    promote(id: number, notes: string): Promise<void> {
      return api.post(`/api/v1/admin/users/${id}/promote`, { notes });
    },
    demote(id: number, notes: string): Promise<void> {
      return api.post(`/api/v1/admin/users/${id}/demote`, { notes });
    },
    resetFrivolousCounter(id: number, notes: string): Promise<void> {
      return api.post(`/api/v1/admin/users/${id}/reset-frivolous-counter`, { notes });
    },
  },

  audit: {
    list(params: {
      targetType?: AdminActionTargetType;
      targetId?: number;
      adminUserId?: number;
      page: number;
      size: number;
    }): Promise<Page<AdminUserModerationRow>> {
      const sp = new URLSearchParams({ page: String(params.page), size: String(params.size) });
      if (params.targetType) sp.set("targetType", params.targetType);
      if (params.targetId !== undefined) sp.set("targetId", String(params.targetId));
      if (params.adminUserId !== undefined) sp.set("adminUserId", String(params.adminUserId));
      return api.get(`/api/v1/admin/audit?${sp.toString()}`);
    },
  },

  auctions: {
    reinstate(id: number, notes: string): Promise<{
      auctionId: number;
      status: string;
      newEndsAt: string;
      suspensionDurationSeconds: number;
    }> {
      return api.post(`/api/v1/admin/auctions/${id}/reinstate`, { notes });
    },
  },
};

export const userReportsApi = {
  submit(auctionId: number, body: ReportRequest): Promise<MyReportResponse> {
    return api.post(`/api/v1/auctions/${auctionId}/report`, body);
  },
  async myReport(auctionId: number): Promise<MyReportResponse | null> {
    const result = await api.get<MyReportResponse | undefined>(
      `/api/v1/auctions/${auctionId}/my-report`
    );
    return result ?? null;
  },
};
