import type { AdminActionTargetType, FraudFlagListStatus, FraudFlagReason } from "./types";

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
  user: (id: number) => [...adminQueryKeys.users(), "detail", id] as const,
  userTab: (id: number, tab: string, filters: { page: number; size: number }) =>
    [...adminQueryKeys.user(id), tab, filters] as const,
  userIps: (id: number) => [...adminQueryKeys.user(id), "ips"] as const,
  audit: (filters: {
    targetType?: AdminActionTargetType;
    targetId?: number;
    adminUserId?: number;
    page: number;
    size: number;
  }) => [...adminQueryKeys.all, "audit", filters] as const,
  myReport: (auctionId: number) => ["auction", auctionId, "my-report"] as const,
};
