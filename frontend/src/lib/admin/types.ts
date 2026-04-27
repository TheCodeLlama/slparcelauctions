export type FraudFlagReason =
  | "OWNERSHIP_CHANGED_TO_UNKNOWN"
  | "PARCEL_DELETED_OR_MERGED"
  | "WORLD_API_FAILURE_THRESHOLD"
  | "ESCROW_WRONG_PAYER"
  | "ESCROW_UNKNOWN_OWNER"
  | "ESCROW_PARCEL_DELETED"
  | "ESCROW_WORLD_API_FAILURE"
  | "BOT_AUTH_BUYER_REVOKED"
  | "BOT_PRICE_DRIFT"
  | "BOT_OWNERSHIP_CHANGED"
  | "BOT_ACCESS_REVOKED"
  | "CANCEL_AND_SELL";

export type AuctionStatus =
  | "DRAFT" | "DRAFT_PAID" | "VERIFICATION_PENDING" | "VERIFICATION_FAILED"
  | "ACTIVE" | "ENDED" | "ESCROW_PENDING" | "ESCROW_FUNDED"
  | "TRANSFER_PENDING" | "COMPLETED" | "CANCELLED" | "EXPIRED"
  | "DISPUTED" | "SUSPENDED";

export type FraudFlagListStatus = "open" | "resolved" | "all";

export type AdminStatsResponse = {
  queues: {
    openFraudFlags: number;
    pendingPayments: number;
    activeDisputes: number;
  };
  platform: {
    activeListings: number;
    totalUsers: number;
    activeEscrows: number;
    completedSales: number;
    lindenGrossVolume: number;
    lindenCommissionEarned: number;
  };
};

export type AdminFraudFlagSummary = {
  id: number;
  reason: FraudFlagReason;
  detectedAt: string;
  auctionId: number | null;
  auctionTitle: string | null;
  auctionStatus: AuctionStatus | null;
  parcelRegionName: string | null;
  parcelLocalId: number | null;
  resolved: boolean;
  resolvedAt: string | null;
  resolvedByDisplayName: string | null;
};

export type LinkedUser = {
  userId: number;
  displayName: string | null;
};

export type AdminFraudFlagDetail = {
  id: number;
  reason: FraudFlagReason;
  detectedAt: string;
  resolvedAt: string | null;
  resolvedByDisplayName: string | null;
  adminNotes: string | null;
  auction: {
    id: number;
    title: string;
    status: AuctionStatus;
    endsAt: string;
    suspendedAt: string | null;
    sellerUserId: number;
    sellerDisplayName: string | null;
  } | null;
  evidenceJson: Record<string, unknown>;
  linkedUsers: Record<string, LinkedUser>;
  siblingOpenFlagCount: number;
};

export type AdminApiError = {
  code: string;
  message: string;
  details: Record<string, unknown>;
};
