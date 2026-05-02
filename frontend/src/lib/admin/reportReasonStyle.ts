import type { ListingReportReason } from "./types";

/**
 * Reason family groups for ListingReportReason.
 *
 * Grouping rationale:
 *   - "fraud"       — SHILL_BIDDING, FRAUDULENT_SELLER: financial fraud / trust violation
 *   - "description" — INACCURATE_DESCRIPTION, WRONG_TAGS: listing accuracy issues
 *   - "duplicate"   — DUPLICATE_LISTING, NOT_ACTUALLY_FOR_SALE: validity / availability
 *   - "tos"         — TOS_VIOLATION: platform rules
 *   - "other"       — OTHER: catch-all
 */
export type ReportReasonFamily = "fraud" | "description" | "duplicate" | "tos" | "other";

export const REPORT_REASON_FAMILY: Record<ListingReportReason, ReportReasonFamily> = {
  SHILL_BIDDING: "fraud",
  FRAUDULENT_SELLER: "fraud",
  INACCURATE_DESCRIPTION: "description",
  WRONG_TAGS: "description",
  DUPLICATE_LISTING: "duplicate",
  NOT_ACTUALLY_FOR_SALE: "duplicate",
  TOS_VIOLATION: "tos",
  OTHER: "other",
};

export const REPORT_REASON_LABEL: Record<ListingReportReason, string> = {
  SHILL_BIDDING: "Shill bidding",
  FRAUDULENT_SELLER: "Fraudulent seller",
  INACCURATE_DESCRIPTION: "Inaccurate description",
  WRONG_TAGS: "Wrong tags",
  DUPLICATE_LISTING: "Duplicate listing",
  NOT_ACTUALLY_FOR_SALE: "Not for sale",
  TOS_VIOLATION: "ToS violation",
  OTHER: "Other",
};

export const REPORT_FAMILY_TONE_CLASSES: Record<ReportReasonFamily, string> = {
  fraud: "bg-danger-bg text-danger",
  description: "bg-info-bg text-info",
  duplicate: "bg-info-bg text-info",
  tos: "bg-danger-bg text-danger",
  other: "bg-bg-hover text-fg-muted",
};
