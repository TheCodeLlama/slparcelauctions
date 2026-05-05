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
    openReports: number;
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

export type ListingReportReason =
  | "INACCURATE_DESCRIPTION"
  | "WRONG_TAGS"
  | "SHILL_BIDDING"
  | "FRAUDULENT_SELLER"
  | "DUPLICATE_LISTING"
  | "NOT_ACTUALLY_FOR_SALE"
  | "TOS_VIOLATION"
  | "OTHER";

export type ListingReportStatus = "OPEN" | "REVIEWED" | "DISMISSED" | "ACTION_TAKEN";

export type BanType = "IP" | "AVATAR" | "BOTH";

export type BanReasonCategory =
  | "SHILL_BIDDING"
  | "FRAUDULENT_SELLER"
  | "TOS_ABUSE"
  | "SPAM"
  | "OTHER";

// AdminActionType and AdminActionTargetType are defined in auditLog.ts (expanded
// set) and re-exported below via `export * from "./auditLog"`.
// Keep this comment so the deletion is traceable.

export type MyReportResponse = {
  id: number;
  subject: string;
  reason: ListingReportReason;
  details: string;
  status: ListingReportStatus;
  createdAt: string;
  updatedAt: string;
};

export type AdminReportListingRow = {
  auctionId: number;
  auctionTitle: string;
  auctionStatus: AuctionStatus;
  parcelRegionName: string | null;
  sellerUserId: number;
  sellerDisplayName: string | null;
  openReportCount: number;
  latestReportAt: string;
};

export type AdminReportDetail = {
  id: number;
  reason: ListingReportReason;
  subject: string;
  details: string;
  status: ListingReportStatus;
  adminNotes: string | null;
  createdAt: string;
  updatedAt: string;
  reviewedAt: string | null;
  reporterUserId: number;
  reporterDisplayName: string | null;
  reporterDismissedReportsCount: number;
  reviewedByDisplayName: string | null;
};

export type AdminBanRow = {
  id: number;
  banType: BanType;
  ipAddress: string | null;
  slAvatarUuid: string | null;
  avatarLinkedUserId: number | null;
  avatarLinkedDisplayName: string | null;
  firstSeenIp: string | null;
  reasonCategory: BanReasonCategory;
  reasonText: string;
  bannedByUserId: number;
  bannedByDisplayName: string | null;
  expiresAt: string | null;
  createdAt: string;
  liftedAt: string | null;
  liftedByUserId: number | null;
  liftedByDisplayName: string | null;
  liftedReason: string | null;
};

export type CreateBanRequest = {
  banType: BanType;
  ipAddress?: string | null;
  slAvatarUuid?: string | null;
  expiresAt?: string | null;
  reasonCategory: BanReasonCategory;
  reasonText: string;
};

export type ReportRequest = {
  subject: string;
  reason: ListingReportReason;
  details: string;
};

export type ActiveBanSummary = {
  id: number;
  banType: BanType;
  reasonText: string;
  expiresAt: string | null;
};

export type AdminUserSummary = {
  publicId: string;
  username: string;
  email: string | null;
  displayName: string | null;
  slAvatarUuid: string | null;
  slDisplayName: string | null;
  role: "USER" | "ADMIN";
  verified: boolean;
  hasActiveBan: boolean;
  completedSales: number;
  cancelledWithBids: number;
  createdAt: string;
};

export type AdminUserDetail = {
  publicId: string;
  username: string;
  email: string | null;
  displayName: string | null;
  slAvatarUuid: string | null;
  slDisplayName: string | null;
  role: "USER" | "ADMIN";
  verified: boolean;
  verifiedAt: string | null;
  createdAt: string;
  completedSales: number;
  cancelledWithBids: number;
  escrowExpiredUnfulfilled: number;
  dismissedReportsCount: number;
  penaltyBalanceOwed: number;
  listingSuspensionUntil: string | null;
  bannedFromListing: boolean;
  activeBan: ActiveBanSummary | null;
};

export type AdminUserListingRow = {
  auctionId: number;
  auctionPublicId: string;
  title: string;
  regionName: string | null;
  status: AuctionStatus;
  endsAt: string;
  finalBidAmount: number | null;
};

export type AdminUserBidRow = {
  bidId: number;
  auctionId: number;
  auctionPublicId: string;
  auctionTitle: string;
  amount: number;
  placedAt: string;
  auctionStatus: AuctionStatus;
};

export type AdminUserCancellationRow = {
  logId: number;
  auctionId: number;
  auctionPublicId: string;
  auctionTitle: string;
  cancelledFromStatus: string;
  hadBids: boolean;
  reason: string;
  penaltyKind: string | null;
  penaltyAmountL: number | null;
  cancelledByAdminId: number | null;
  cancelledAt: string;
};

export type AdminUserReportRow = {
  reportId: number;
  auctionId: number;
  auctionPublicId: string;
  auctionTitle: string;
  reason: string;
  status: string;
  direction: "FILED_BY" | "AGAINST_LISTING";
  createdAt: string;
  updatedAt: string;
};

export type AdminUserFraudFlagRow = {
  flagId: number;
  auctionId: number | null;
  auctionPublicId: string | null;
  auctionTitle: string | null;
  reason: string;
  resolved: boolean;
  detectedAt: string;
};

export type AdminUserModerationRow = {
  actionId: number;
  actionType: string;
  adminDisplayName: string | null;
  notes: string | null;
  createdAt: string;
};

export type UserIpProjection = {
  ipAddress: string;
  firstSeenAt: string;
  lastSeenAt: string;
  sessionCount: number;
};

// ---------- Admin wallet ops ----------

export type AdminWalletPendingWithdrawalStatus = "QUEUED" | "IN_FLIGHT";

export type AdminWalletPendingWithdrawal = {
  terminalCommandId: number;
  amount: number;
  recipientUuid: string;
  queuedAt: string;
  dispatchedAt: string | null;
  attemptCount: number;
  status: AdminWalletPendingWithdrawalStatus;
  canForceFinalize: boolean;
};

export type AdminWalletSnapshot = {
  publicId: string;
  username: string;
  balanceLindens: number;
  reservedLindens: number;
  availableLindens: number;
  penaltyBalanceOwed: number;
  walletFrozenAt: string | null;
  walletFrozenReason: string | null;
  walletFrozenByAdminId: number | null;
  walletDormancyStartedAt: string | null;
  walletDormancyPhase: number | null;
  walletTermsAcceptedAt: string | null;
  walletTermsVersion: string | null;
  pendingWithdrawals: AdminWalletPendingWithdrawal[];
};

export type AdminWalletLedgerEntryType =
  | "DEPOSIT"
  | "WITHDRAW_QUEUED"
  | "WITHDRAW_COMPLETED"
  | "WITHDRAW_REVERSED"
  | "BID_RESERVED"
  | "BID_RELEASED"
  | "ESCROW_DEBIT"
  | "ESCROW_REFUND"
  | "LISTING_FEE_DEBIT"
  | "LISTING_FEE_REFUND"
  | "PENALTY_DEBIT"
  | "ADJUSTMENT";

export type AdminWalletLedgerRow = {
  entryId: number;
  entryType: AdminWalletLedgerEntryType;
  amount: number;
  balanceAfter: number;
  reservedAfter: number;
  createdAt: string;
  description: string | null;
  refType: string | null;
  refId: number | null;
  createdByAdminId: number | null;
};

export type AdminWalletAdjustRequest = {
  amount: number;
  notes: string;
  overrideReservationFloor: boolean;
};

export type AdminWalletForgivePenaltyRequest = {
  amount: number;
  notes: string;
};

export type AdminWalletNotesRequest = {
  notes: string;
};

export * from "./auditLog";
export * from "./disputes";
export * from "./infrastructure";
