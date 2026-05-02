import type { FraudFlagReason } from "./types";

export type ReasonFamily = "ownership" | "bot" | "escrow" | "cancel-and-sell";

export const REASON_FAMILY: Record<FraudFlagReason, ReasonFamily> = {
  OWNERSHIP_CHANGED_TO_UNKNOWN: "ownership",
  PARCEL_DELETED_OR_MERGED: "ownership",
  WORLD_API_FAILURE_THRESHOLD: "ownership",
  ESCROW_WRONG_PAYER: "escrow",
  ESCROW_UNKNOWN_OWNER: "escrow",
  ESCROW_PARCEL_DELETED: "escrow",
  ESCROW_WORLD_API_FAILURE: "escrow",
  BOT_AUTH_BUYER_REVOKED: "bot",
  BOT_PRICE_DRIFT: "bot",
  BOT_OWNERSHIP_CHANGED: "bot",
  BOT_ACCESS_REVOKED: "bot",
  CANCEL_AND_SELL: "cancel-and-sell",
};

export const REASON_LABEL: Record<FraudFlagReason, string> = {
  OWNERSHIP_CHANGED_TO_UNKNOWN: "Owner changed",
  PARCEL_DELETED_OR_MERGED: "Parcel deleted",
  WORLD_API_FAILURE_THRESHOLD: "World API failures",
  ESCROW_WRONG_PAYER: "Wrong payer",
  ESCROW_UNKNOWN_OWNER: "Escrow owner unknown",
  ESCROW_PARCEL_DELETED: "Escrow parcel deleted",
  ESCROW_WORLD_API_FAILURE: "Escrow API failures",
  BOT_AUTH_BUYER_REVOKED: "Bot auth revoked",
  BOT_PRICE_DRIFT: "Bot price drift",
  BOT_OWNERSHIP_CHANGED: "Bot owner changed",
  BOT_ACCESS_REVOKED: "Bot access revoked",
  CANCEL_AND_SELL: "Cancel-and-sell",
};

export const FAMILY_TONE_CLASSES: Record<ReasonFamily, string> = {
  ownership: "bg-danger-bg text-danger",
  bot: "bg-info-bg text-info",
  escrow: "bg-info-bg text-info",
  "cancel-and-sell": "bg-brand-soft text-brand",
};
