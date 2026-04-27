import type { FraudFlagListStatus, FraudFlagReason } from "./types";

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
};
