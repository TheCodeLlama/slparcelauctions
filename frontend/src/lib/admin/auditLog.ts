export type AdminActionType =
  | "DISMISS_REPORT" | "WARN_SELLER_FROM_REPORT" | "SUSPEND_LISTING_FROM_REPORT"
  | "CANCEL_LISTING_FROM_REPORT" | "CREATE_BAN" | "LIFT_BAN"
  | "PROMOTE_USER" | "DEMOTE_USER" | "RESET_FRIVOLOUS_COUNTER"
  | "REINSTATE_LISTING" | "DISPUTE_RESOLVED" | "LISTING_CANCELLED_VIA_DISPUTE"
  | "WITHDRAWAL_REQUESTED" | "OWNERSHIP_RECHECK_INVOKED"
  | "TERMINAL_SECRET_ROTATED" | "USER_DELETED_BY_ADMIN";

export type AdminActionTargetType =
  | "REPORT" | "BAN" | "USER" | "AUCTION" | "FRAUD_FLAG"
  | "DISPUTE" | "WITHDRAWAL" | "TERMINAL_SECRET";

export type AdminAuditLogRow = {
  id: number;
  occurredAt: string;
  actionType: AdminActionType;
  adminUserId: number;
  adminEmail: string | null;
  targetType: AdminActionTargetType | null;
  targetId: number | null;
  notes: string | null;
  details: Record<string, unknown>;
};

export type AdminAuditLogFilters = {
  actionType?: AdminActionType;
  targetType?: AdminActionTargetType;
  adminUserId?: number;
  from?: string;
  to?: string;
  q?: string;
  page?: number;
  size?: number;
};
