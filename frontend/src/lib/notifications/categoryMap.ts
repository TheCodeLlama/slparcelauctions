import type { ComponentType } from "react";
import {
  Bell, Bolt, Wallet, Star, Trophy, Clock, AlertTriangle,
  CheckCircle2, AlertOctagon, BadgeCheck, XCircle, Pause,
} from "@/components/ui/icons";
import type { ToastKind } from "@/components/ui/Toast";
import type { NotificationCategory, NotificationGroup } from "./types";

export interface CategoryMapEntry {
  group: NotificationGroup;
  icon: ComponentType<{ className?: string }>;
  iconBgClass: string;
  toastVariant: ToastKind;
  deeplink: (data: Record<string, unknown>) => string;
  action?: {
    label: string;
    href: (data: Record<string, unknown>) => string;
  };
}

export const categoryMap: Record<NotificationCategory, CategoryMapEntry> = {
  OUTBID: {
    group: "bidding",
    icon: Bolt,
    iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "warning",
    deeplink: (d) => `/auction/${d.auctionId}`,
    action: { label: "Place a new bid", href: (d) => `/auction/${d.auctionId}#bid-panel` },
  },
  PROXY_EXHAUSTED: {
    group: "bidding",
    icon: Bolt,
    iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "warning",
    deeplink: (d) => `/auction/${d.auctionId}`,
    action: { label: "Increase proxy", href: (d) => `/auction/${d.auctionId}#bid-panel` },
  },
  AUCTION_WON: {
    group: "auction_result",
    icon: Trophy,
    iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "success",
    deeplink: (d) => `/auction/${d.auctionId}/escrow`,
    action: { label: "Pay escrow", href: (d) => `/auction/${d.auctionId}/escrow` },
  },
  AUCTION_LOST: {
    group: "auction_result",
    icon: Trophy,
    iconBgClass: "bg-surface-container-high text-on-surface",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionId}`,
  },
  AUCTION_ENDED_SOLD: {
    group: "auction_result",
    icon: Trophy,
    iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionId}`,
  },
  AUCTION_ENDED_RESERVE_NOT_MET: {
    group: "auction_result",
    icon: AlertTriangle,
    iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionId}`,
  },
  AUCTION_ENDED_NO_BIDS: {
    group: "auction_result",
    icon: Pause,
    iconBgClass: "bg-surface-container-high text-on-surface",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionId}`,
  },
  AUCTION_ENDED_BOUGHT_NOW: {
    group: "auction_result",
    icon: Trophy,
    iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionId}`,
  },
  ESCROW_FUNDED: {
    group: "escrow",
    icon: Wallet,
    iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionId}/escrow`,
    action: { label: "View escrow", href: (d) => `/auction/${d.auctionId}/escrow` },
  },
  ESCROW_TRANSFER_CONFIRMED: {
    group: "escrow",
    icon: BadgeCheck,
    iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "success",
    deeplink: (d) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_PAYOUT: {
    group: "escrow",
    icon: CheckCircle2,
    iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "success",
    deeplink: (d) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_EXPIRED: {
    group: "escrow",
    icon: Clock,
    iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "warning",
    deeplink: (d) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_DISPUTED: {
    group: "escrow",
    icon: AlertOctagon,
    iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "error",
    deeplink: (d) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_FROZEN: {
    group: "escrow",
    icon: AlertOctagon,
    iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "error",
    deeplink: (d) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_PAYOUT_STALLED: {
    group: "escrow",
    icon: AlertTriangle,
    iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "warning",
    deeplink: (d) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_TRANSFER_REMINDER: {
    group: "escrow",
    icon: Clock,
    iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionId}/escrow`,
    action: { label: "View escrow", href: (d) => `/auction/${d.auctionId}/escrow` },
  },
  LISTING_VERIFIED: {
    group: "listing_status",
    icon: BadgeCheck,
    iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "success",
    deeplink: (d) => `/auction/${d.auctionId}`,
  },
  LISTING_SUSPENDED: {
    group: "listing_status",
    icon: XCircle,
    iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "error",
    deeplink: () => `/dashboard/listings`,
  },
  LISTING_REVIEW_REQUIRED: {
    group: "listing_status",
    icon: AlertOctagon,
    iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "error",
    deeplink: () => `/dashboard/listings`,
  },
  LISTING_CANCELLED_BY_SELLER: {
    group: "listing_status",
    icon: XCircle,
    iconBgClass: "bg-surface-container-high text-on-surface",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionId}`,
  },
  REVIEW_RECEIVED: {
    group: "reviews",
    icon: Star,
    iconBgClass: "bg-tertiary-container text-on-tertiary-container",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionId}`,
  },
  SYSTEM_ANNOUNCEMENT: {
    group: "system",
    icon: Bell,
    iconBgClass: "bg-surface-container-high text-on-surface",
    toastVariant: "info",
    deeplink: () => `/notifications`,
  },
};

export function categoryConfigOrFallback(category: string): CategoryMapEntry {
  const entry = (categoryMap as Record<string, CategoryMapEntry | undefined>)[category];
  if (entry) return entry;
  return {
    group: "system",
    icon: Bell,
    iconBgClass: "bg-surface-container-high text-on-surface",
    toastVariant: "info",
    deeplink: () => `/notifications`,
  };
}
