import type { ComponentType } from "react";
import {
  Bell, Bolt, Wallet, Star, Trophy, Clock, AlertTriangle,
  CheckCircle2, AlertOctagon, BadgeCheck, XCircle, Pause,
  MessageSquare,
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
    iconBgClass: "bg-danger-bg text-danger",
    toastVariant: "warning",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}`,
    action: { label: "Place a new bid", href: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}#bid-panel` },
  },
  PROXY_EXHAUSTED: {
    group: "bidding",
    icon: Bolt,
    iconBgClass: "bg-danger-bg text-danger",
    toastVariant: "warning",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}`,
    action: { label: "Increase proxy", href: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}#bid-panel` },
  },
  AUCTION_WON: {
    group: "auction_result",
    icon: Trophy,
    iconBgClass: "bg-brand-soft text-brand",
    toastVariant: "success",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow`,
    action: { label: "View escrow", href: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow` },
  },
  AUCTION_LOST: {
    group: "auction_result",
    icon: Trophy,
    iconBgClass: "bg-bg-hover text-fg",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}`,
  },
  AUCTION_ENDED_SOLD: {
    group: "auction_result",
    icon: Trophy,
    iconBgClass: "bg-brand-soft text-brand",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}`,
  },
  AUCTION_ENDED_RESERVE_NOT_MET: {
    group: "auction_result",
    icon: AlertTriangle,
    iconBgClass: "bg-danger-bg text-danger",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}`,
  },
  AUCTION_ENDED_NO_BIDS: {
    group: "auction_result",
    icon: Pause,
    iconBgClass: "bg-bg-hover text-fg",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}`,
  },
  AUCTION_ENDED_BOUGHT_NOW: {
    group: "auction_result",
    icon: Trophy,
    iconBgClass: "bg-brand-soft text-brand",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}`,
  },
  ESCROW_FUNDED: {
    group: "escrow",
    icon: Wallet,
    iconBgClass: "bg-brand-soft text-brand",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow`,
    action: { label: "View escrow", href: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow` },
  },
  ESCROW_SELL_TO_SET: {
    group: "escrow",
    icon: BadgeCheck,
    iconBgClass: "bg-brand-soft text-brand",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow`,
    action: { label: "Buy parcel", href: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow` },
  },
  ESCROW_TRANSFER_CONFIRMED: {
    group: "escrow",
    icon: BadgeCheck,
    iconBgClass: "bg-brand-soft text-brand",
    toastVariant: "success",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow`,
  },
  ESCROW_PAYOUT: {
    group: "escrow",
    icon: CheckCircle2,
    iconBgClass: "bg-brand-soft text-brand",
    toastVariant: "success",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow`,
  },
  ESCROW_EXPIRED: {
    group: "escrow",
    icon: Clock,
    iconBgClass: "bg-danger-bg text-danger",
    toastVariant: "warning",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow`,
  },
  ESCROW_DISPUTED: {
    group: "escrow",
    icon: AlertOctagon,
    iconBgClass: "bg-danger-bg text-danger",
    toastVariant: "error",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow`,
  },
  ESCROW_FROZEN: {
    group: "escrow",
    icon: AlertOctagon,
    iconBgClass: "bg-danger-bg text-danger",
    toastVariant: "error",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow`,
  },
  ESCROW_PAYOUT_STALLED: {
    group: "escrow",
    icon: AlertTriangle,
    iconBgClass: "bg-danger-bg text-danger",
    toastVariant: "warning",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow`,
  },
  ESCROW_TRANSFER_REMINDER: {
    group: "escrow",
    icon: Clock,
    iconBgClass: "bg-brand-soft text-brand",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow`,
    action: { label: "View escrow", href: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow` },
  },
  LISTING_VERIFIED: {
    group: "listing_status",
    icon: BadgeCheck,
    iconBgClass: "bg-brand-soft text-brand",
    toastVariant: "success",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}`,
  },
  LISTING_SUSPENDED: {
    group: "listing_status",
    icon: XCircle,
    iconBgClass: "bg-danger-bg text-danger",
    toastVariant: "error",
    deeplink: () => `/dashboard/listings`,
  },
  LISTING_REVIEW_REQUIRED: {
    group: "listing_status",
    icon: AlertOctagon,
    iconBgClass: "bg-danger-bg text-danger",
    toastVariant: "error",
    deeplink: () => `/dashboard/listings`,
  },
  LISTING_CANCELLED_BY_SELLER: {
    group: "listing_status",
    icon: XCircle,
    iconBgClass: "bg-bg-hover text-fg",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}`,
  },
  LISTING_CANCELLED_DURING_ESCROW: {
    group: "escrow",
    icon: XCircle,
    iconBgClass: "bg-danger-bg text-danger",
    toastVariant: "warning",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow`,
    action: { label: "View escrow", href: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}/escrow` },
  },
  REVIEW_RECEIVED: {
    group: "reviews",
    icon: Star,
    iconBgClass: "bg-info-bg text-info",
    toastVariant: "info",
    deeplink: (d) => `/auction/${d.auctionPublicId ?? d.auctionId}`,
  },
  SUPPORT_TICKET_ADMIN_REPLIED: {
    group: "system",
    icon: MessageSquare,
    iconBgClass: "bg-info-bg text-info",
    toastVariant: "info",
    deeplink: (d) => `/support/${d.ticketPublicId}`,
    action: { label: "View ticket", href: (d) => `/support/${d.ticketPublicId}` },
  },
  SUPPORT_TICKET_RESOLVED: {
    group: "system",
    icon: MessageSquare,
    iconBgClass: "bg-success-bg text-success",
    toastVariant: "success",
    deeplink: (d) => `/support/${d.ticketPublicId}`,
  },
  SUPPORT_TICKET_OPENED: {
    group: "system",
    icon: MessageSquare,
    iconBgClass: "bg-info-bg text-info",
    toastVariant: "info",
    deeplink: (d) => `/admin/support/${d.ticketPublicId}`,
    action: { label: "Open queue", href: (d) => `/admin/support/${d.ticketPublicId}` },
  },
  SUPPORT_TICKET_USER_REPLIED: {
    group: "system",
    icon: MessageSquare,
    iconBgClass: "bg-info-bg text-info",
    toastVariant: "info",
    deeplink: (d) => `/admin/support/${d.ticketPublicId}`,
    action: { label: "Open ticket", href: (d) => `/admin/support/${d.ticketPublicId}` },
  },
  SYSTEM_ANNOUNCEMENT: {
    group: "system",
    icon: Bell,
    iconBgClass: "bg-bg-hover text-fg",
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
    iconBgClass: "bg-bg-hover text-fg",
    toastVariant: "info",
    deeplink: () => `/notifications`,
  };
}
