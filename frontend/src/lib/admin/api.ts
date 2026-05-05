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
  AdminWalletAdjustRequest,
  AdminWalletForgivePenaltyRequest,
  AdminWalletLedgerRow,
  AdminWalletNotesRequest,
  AdminWalletSnapshot,
  AdminActionTargetType,
  BanType,
  CreateBanRequest,
  FraudFlagListStatus,
  FraudFlagReason,
  MyReportResponse,
  ReportRequest,
  UserIpProjection,
} from "./types";
import type { AdminAuditLogFilters, AdminAuditLogRow } from "./auditLog";
import type {
  AdminDisputeFilters,
  AdminDisputeQueueRow,
  AdminDisputeDetail,
  AdminDisputeResolveRequest,
  AdminDisputeResolveResponse,
} from "./disputes";
import type {
  BotPoolHealthRow,
  AdminTerminalRow,
  TerminalRotationResponse,
  ReconciliationRunRow,
  WithdrawalRow,
  WithdrawalRequest,
  AdminOwnershipRecheckResponse,
} from "./infrastructure";
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
    detail(publicId: string): Promise<AdminUserDetail> {
      return api.get(`/api/v1/admin/users/${publicId}`);
    },
    listings(publicId: string, page: number, size: number): Promise<Page<AdminUserListingRow>> {
      return api.get(`/api/v1/admin/users/${publicId}/listings?page=${page}&size=${size}`);
    },
    bids(publicId: string, page: number, size: number): Promise<Page<AdminUserBidRow>> {
      return api.get(`/api/v1/admin/users/${publicId}/bids?page=${page}&size=${size}`);
    },
    cancellations(publicId: string, page: number, size: number): Promise<Page<AdminUserCancellationRow>> {
      return api.get(`/api/v1/admin/users/${publicId}/cancellations?page=${page}&size=${size}`);
    },
    reports(publicId: string, page: number, size: number): Promise<Page<AdminUserReportRow>> {
      return api.get(`/api/v1/admin/users/${publicId}/reports?page=${page}&size=${size}`);
    },
    fraudFlags(publicId: string, page: number, size: number): Promise<Page<AdminUserFraudFlagRow>> {
      return api.get(`/api/v1/admin/users/${publicId}/fraud-flags?page=${page}&size=${size}`);
    },
    moderation(publicId: string, page: number, size: number): Promise<Page<AdminUserModerationRow>> {
      return api.get(`/api/v1/admin/users/${publicId}/moderation?page=${page}&size=${size}`);
    },
    ips(publicId: string): Promise<UserIpProjection[]> {
      return api.get(`/api/v1/admin/users/${publicId}/ips`);
    },
    promote(publicId: string, notes: string): Promise<void> {
      return api.post(`/api/v1/admin/users/${publicId}/promote`, { notes });
    },
    demote(publicId: string, notes: string): Promise<void> {
      return api.post(`/api/v1/admin/users/${publicId}/demote`, { notes });
    },
    resetFrivolousCounter(publicId: string, notes: string): Promise<void> {
      return api.post(`/api/v1/admin/users/${publicId}/reset-frivolous-counter`, { notes });
    },
    delete(publicId: string, adminNote: string): Promise<void> {
      return api.delete(`/api/v1/admin/users/${publicId}`, { body: { adminNote } });
    },
    wallet: {
      snapshot(publicId: string): Promise<AdminWalletSnapshot> {
        return api.get(`/api/v1/admin/users/${publicId}/wallet`);
      },
      ledger(publicId: string, page: number, size: number): Promise<Page<AdminWalletLedgerRow>> {
        return api.get(`/api/v1/admin/users/${publicId}/wallet/ledger?page=${page}&size=${size}`);
      },
      adjust(publicId: string, body: AdminWalletAdjustRequest): Promise<AdminWalletSnapshot> {
        return api.post(`/api/v1/admin/users/${publicId}/wallet/adjust`, body);
      },
      freeze(publicId: string, body: AdminWalletNotesRequest): Promise<AdminWalletSnapshot> {
        return api.post(`/api/v1/admin/users/${publicId}/wallet/freeze`, body);
      },
      unfreeze(publicId: string, body: AdminWalletNotesRequest): Promise<AdminWalletSnapshot> {
        return api.post(`/api/v1/admin/users/${publicId}/wallet/unfreeze`, body);
      },
      forgivePenalty(publicId: string, body: AdminWalletForgivePenaltyRequest): Promise<AdminWalletSnapshot> {
        return api.post(`/api/v1/admin/users/${publicId}/wallet/forgive-penalty`, body);
      },
      resetDormancy(publicId: string, body: AdminWalletNotesRequest): Promise<AdminWalletSnapshot> {
        return api.post(`/api/v1/admin/users/${publicId}/wallet/reset-dormancy`, body);
      },
      clearTerms(publicId: string, body: AdminWalletNotesRequest): Promise<AdminWalletSnapshot> {
        return api.post(`/api/v1/admin/users/${publicId}/wallet/clear-terms`, body);
      },
      forceCompleteWithdrawal(publicId: string, terminalCommandId: number, body: AdminWalletNotesRequest):
          Promise<AdminWalletSnapshot> {
        return api.post(`/api/v1/admin/users/${publicId}/wallet/withdrawals/${terminalCommandId}/force-complete`, body);
      },
      forceFailWithdrawal(publicId: string, terminalCommandId: number, body: AdminWalletNotesRequest):
          Promise<AdminWalletSnapshot> {
        return api.post(`/api/v1/admin/users/${publicId}/wallet/withdrawals/${terminalCommandId}/force-fail`, body);
      },
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

  auditLog: {
    list(filters: AdminAuditLogFilters): Promise<Page<AdminAuditLogRow>> {
      const search = new URLSearchParams();
      if (filters.actionType) search.set("actionType", filters.actionType);
      if (filters.targetType) search.set("targetType", filters.targetType);
      if (filters.adminUserId !== undefined) search.set("adminUserId", String(filters.adminUserId));
      if (filters.from) search.set("from", filters.from);
      if (filters.to) search.set("to", filters.to);
      if (filters.q) search.set("q", filters.q);
      search.set("page", String(filters.page ?? 0));
      search.set("size", String(filters.size ?? 50));
      return api.get(`/api/v1/admin/audit-log?${search.toString()}`);
    },
    exportUrl(filters: AdminAuditLogFilters): string {
      const search = new URLSearchParams();
      if (filters.actionType) search.set("actionType", filters.actionType);
      if (filters.targetType) search.set("targetType", filters.targetType);
      if (filters.adminUserId !== undefined) search.set("adminUserId", String(filters.adminUserId));
      if (filters.from) search.set("from", filters.from);
      if (filters.to) search.set("to", filters.to);
      if (filters.q) search.set("q", filters.q);
      return `/api/v1/admin/audit-log/export?${search.toString()}`;
    },
  },

  auctions: {
    reinstate(publicId: string, notes: string): Promise<{
      auctionId: number;
      status: string;
      newEndsAt: string;
      suspensionDurationSeconds: number;
    }> {
      return api.post(`/api/v1/admin/auctions/${publicId}/reinstate`, { notes });
    },
  },

  disputes: {
    list(filters: AdminDisputeFilters): Promise<Page<AdminDisputeQueueRow>> {
      const search = new URLSearchParams();
      if (filters.status) search.set("status", filters.status);
      if (filters.reasonCategory) search.set("reasonCategory", filters.reasonCategory);
      search.set("page", String(filters.page ?? 0));
      search.set("size", String(filters.size ?? 20));
      return api.get(`/api/v1/admin/disputes?${search.toString()}`);
    },
    detail(escrowId: number): Promise<AdminDisputeDetail> {
      return api.get(`/api/v1/admin/disputes/${escrowId}`);
    },
    resolve(escrowId: number, body: AdminDisputeResolveRequest): Promise<AdminDisputeResolveResponse> {
      return api.post(`/api/v1/admin/disputes/${escrowId}/resolve`, body);
    },
  },

  botPool: {
    health(): Promise<BotPoolHealthRow[]> {
      return api.get("/api/v1/admin/bot-pool/health");
    },
  },

  terminals: {
    list(): Promise<AdminTerminalRow[]> {
      return api.get("/api/v1/admin/terminals");
    },
    rotateSecret(): Promise<TerminalRotationResponse> {
      return api.post("/api/v1/admin/terminals/rotate-secret", {});
    },
    deactivate(terminalId: string): Promise<void> {
      return api.delete(`/api/v1/admin/terminals/${encodeURIComponent(terminalId)}`);
    },
  },

  reconciliation: {
    runs(days: number = 7): Promise<ReconciliationRunRow[]> {
      return api.get(`/api/v1/admin/reconciliation/runs?days=${days}`);
    },
  },

  withdrawals: {
    list(page: number = 0, size: number = 20): Promise<Page<WithdrawalRow>> {
      return api.get(`/api/v1/admin/withdrawals?page=${page}&size=${size}`);
    },
    create(body: WithdrawalRequest): Promise<WithdrawalRow> {
      return api.post("/api/v1/admin/withdrawals", body);
    },
    available(): Promise<{ available: number }> {
      return api.get("/api/v1/admin/withdrawals/available");
    },
  },

  ownershipRecheck: {
    recheck(auctionId: number): Promise<AdminOwnershipRecheckResponse> {
      return api.post(`/api/v1/admin/auctions/${auctionId}/recheck-ownership`, {});
    },
  },
};

export const userReportsApi = {
  submit(auctionPublicId: string, body: ReportRequest): Promise<MyReportResponse> {
    return api.post(`/api/v1/auctions/${auctionPublicId}/report`, body);
  },
  async myReport(auctionPublicId: string): Promise<MyReportResponse | null> {
    const result = await api.get<MyReportResponse | undefined>(
      `/api/v1/auctions/${auctionPublicId}/my-report`
    );
    return result ?? null;
  },
};
