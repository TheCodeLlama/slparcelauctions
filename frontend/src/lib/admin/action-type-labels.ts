import type { AdminActionType } from "./auditLog";

/**
 * Sub-project G §15 — human-readable label for every {@link AdminActionType}
 * value the audit log can carry. Single source of truth for the admin UI's
 * row-rendering layer. The fallback in {@link labelFor} handles any future
 * enum value that ships before this map updates.
 */
export const ACTION_TYPE_LABELS: Record<AdminActionType, string> = {
  DISMISS_REPORT: "Dismiss report",
  WARN_SELLER_FROM_REPORT: "Warn seller (from report)",
  SUSPEND_LISTING_FROM_REPORT: "Suspend listing (from report)",
  CANCEL_LISTING_FROM_REPORT: "Cancel listing (from report)",
  CREATE_BAN: "Create ban",
  LIFT_BAN: "Lift ban",
  PROMOTE_USER: "Promote user",
  DEMOTE_USER: "Demote user",
  RESET_FRIVOLOUS_COUNTER: "Reset frivolous-reporter counter",
  REINSTATE_LISTING: "Reinstate listing",
  DISPUTE_RESOLVED: "Resolve dispute",
  LISTING_CANCELLED_VIA_DISPUTE: "Cancel listing via dispute",
  WITHDRAWAL_REQUESTED: "Withdrawal requested",
  OWNERSHIP_RECHECK_INVOKED: "Ownership recheck",
  TERMINAL_SECRET_ROTATED: "Rotate terminal secret",
  USER_DELETED_BY_ADMIN: "Delete user",
  WALLET_ADJUST: "Wallet adjustment",
  WALLET_FREEZE: "Freeze wallet",
  WALLET_UNFREEZE: "Unfreeze wallet",
  WALLET_FORGIVE_PENALTY: "Forgive wallet penalty",
  WALLET_RESET_DORMANCY: "Reset wallet dormancy",
  WALLET_CLEAR_TERMS: "Clear wallet terms acceptance",
  WITHDRAWAL_FORCE_COMPLETE: "Force-complete withdrawal",
  WITHDRAWAL_FORCE_FAIL: "Force-fail withdrawal",
  PARCEL_TAG_CREATED: "Create parcel tag",
  PARCEL_TAG_UPDATED: "Update parcel tag",
  PARCEL_TAG_TOGGLED_ACTIVE: "Toggle parcel tag active",
  PARCEL_TAG_CATEGORY_CREATED: "Create parcel tag category",
  PARCEL_TAG_CATEGORY_UPDATED: "Update parcel tag category",
  PARCEL_TAG_CATEGORY_TOGGLED_ACTIVE: "Toggle parcel tag category active",
  FEATURE_LISTING: "Feature listing",
  UNFEATURE_LISTING: "Unfeature listing",
  REALTY_GROUP_EDIT: "Edit realty group",
  REALTY_GROUP_DISSOLVE: "Dissolve realty group",
  REALTY_GROUP_MEMBER_REMOVE: "Remove realty group member",
  REALTY_GROUP_SUSPEND: "Suspend realty group",
  REALTY_GROUP_UNSUSPEND: "Unsuspend realty group",
  REALTY_GROUP_BAN: "Ban realty group",
  REALTY_GROUP_UNBAN: "Unban realty group",
  REALTY_GROUP_FRAUD_FLAG: "Flag realty group for fraud",
  REALTY_GROUP_REPORT_RESOLVE: "Resolve realty group report",
  REALTY_GROUP_REPORT_DISMISS: "Dismiss realty group report",
  REALTY_GROUP_BULK_SUSPEND: "Bulk-suspend group listings",
  REALTY_GROUP_BULK_REINSTATE: "Bulk-reinstate group listings",
  REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN: "Bulk-suspend expiry sweep",
  REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER: "Force-unregister SL group",
  REALTY_GROUP_SL_GROUP_DRIFT_ACK: "Acknowledge SL group drift",
  REALTY_GROUP_SL_GROUP_RECHECK: "Recheck SL group",
  REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT: "Realty group wallet adjustment",
};

/**
 * Map an {@link AdminActionType} value to its human label, falling back to
 * a title-cased version of the raw enum name when an entry is missing
 * (e.g. a backend-only addition the frontend hasn't caught up to).
 */
export function labelFor(action: AdminActionType): string {
  return ACTION_TYPE_LABELS[action] ?? toTitleCase(action);
}

function toTitleCase(raw: string): string {
  return raw
    .toLowerCase()
    .split("_")
    .map((w) => (w.length === 0 ? w : w.charAt(0).toUpperCase() + w.slice(1)))
    .join(" ");
}
