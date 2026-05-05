import type { AdminActionTargetType, FraudFlagListStatus, FraudFlagReason } from "./types";
import type { AdminDisputeFilters } from "./disputes";
import type { AdminAuditLogFilters } from "./auditLog";

export const adminQueryKeys = {
  all: ["admin"] as const,
  stats: () => [...adminQueryKeys.all, "stats"] as const,
  fraudFlags: () => [...adminQueryKeys.all, "fraud-flags"] as const,
  fraudFlagsList: (filters: {
    status: FraudFlagListStatus;
    reasons: FraudFlagReason[];
    page: number;
    size: number;
  }) => [...adminQueryKeys.fraudFlags(), "list", filters] as const,
  fraudFlagDetail: (flagId: number) =>
    [...adminQueryKeys.fraudFlags(), "detail", flagId] as const,
  reports: () => [...adminQueryKeys.all, "reports"] as const,
  reportsList: (filters: { status: string; page: number; size: number }) =>
    [...adminQueryKeys.reports(), "list", filters] as const,
  reportListing: (auctionId: number) =>
    [...adminQueryKeys.reports(), "listing", auctionId] as const,
  reportDetail: (id: number) =>
    [...adminQueryKeys.reports(), "detail", id] as const,
  bans: () => [...adminQueryKeys.all, "bans"] as const,
  bansList: (filters: { status: string; type?: string; page: number; size: number }) =>
    [...adminQueryKeys.bans(), "list", filters] as const,
  users: () => [...adminQueryKeys.all, "users"] as const,
  usersList: (filters: { search?: string; page: number; size: number }) =>
    [...adminQueryKeys.users(), "list", filters] as const,
  user: (publicId: string) => [...adminQueryKeys.users(), "detail", publicId] as const,
  userTab: (publicId: string, tab: string, filters: { page: number; size: number }) =>
    [...adminQueryKeys.user(publicId), tab, filters] as const,
  userIps: (publicId: string) => [...adminQueryKeys.user(publicId), "ips"] as const,
  wallet: (publicId: string) => [...adminQueryKeys.user(publicId), "wallet"] as const,
  walletLedger: (publicId: string, filters: { page: number; size: number }) =>
    [...adminQueryKeys.wallet(publicId), "ledger", filters] as const,
  audit: (filters: {
    targetType?: AdminActionTargetType;
    targetId?: number;
    adminUserId?: number;
    page: number;
    size: number;
  }) => [...adminQueryKeys.all, "audit", filters] as const,
  myReport: (auctionPublicId: string) => ["auction", auctionPublicId, "my-report"] as const,

  auditLog: () => [...adminQueryKeys.all, "audit-log"] as const,
  auditLogList: (filters: AdminAuditLogFilters) =>
    [...adminQueryKeys.auditLog(), "list", filters] as const,

  disputes: () => [...adminQueryKeys.all, "disputes"] as const,
  disputesList: (filters: AdminDisputeFilters) =>
    [...adminQueryKeys.disputes(), "list", filters] as const,
  disputeDetail: (escrowId: number) =>
    [...adminQueryKeys.disputes(), "detail", escrowId] as const,

  botPool: () => [...adminQueryKeys.all, "bot-pool"] as const,

  terminals: () => [...adminQueryKeys.all, "terminals"] as const,

  reconciliation: () => [...adminQueryKeys.all, "reconciliation"] as const,
  reconciliationRuns: (days: number) =>
    [...adminQueryKeys.reconciliation(), "runs", days] as const,

  withdrawals: () => [...adminQueryKeys.all, "withdrawals"] as const,
  withdrawalsList: (page: number, size: number) =>
    [...adminQueryKeys.withdrawals(), "list", page, size] as const,
  withdrawalsAvailable: () =>
    [...adminQueryKeys.withdrawals(), "available"] as const,
};
