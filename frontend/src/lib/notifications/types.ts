export type NotificationGroup =
  | "bidding"
  | "auction_result"
  | "escrow"
  | "listing_status"
  | "reviews"
  | "realty_group"
  | "marketing"
  | "system";

export type NotificationCategory =
  | "OUTBID"
  | "PROXY_EXHAUSTED"
  | "AUCTION_WON"
  | "AUCTION_LOST"
  | "AUCTION_ENDED_SOLD"
  | "AUCTION_ENDED_RESERVE_NOT_MET"
  | "AUCTION_ENDED_NO_BIDS"
  | "AUCTION_ENDED_BOUGHT_NOW"
  | "ESCROW_FUNDED"
  | "ESCROW_TRANSFER_CONFIRMED"
  | "ESCROW_PAYOUT"
  | "ESCROW_EXPIRED"
  | "ESCROW_DISPUTED"
  | "ESCROW_FROZEN"
  | "ESCROW_PAYOUT_STALLED"
  | "ESCROW_TRANSFER_REMINDER"
  | "LISTING_VERIFIED"
  | "LISTING_SUSPENDED"
  | "LISTING_REVIEW_REQUIRED"
  | "LISTING_CANCELLED_BY_SELLER"
  | "REVIEW_RECEIVED"
  | "SYSTEM_ANNOUNCEMENT";

export type NotificationDataMap = {
  OUTBID: { auctionId: number; parcelName: string; currentBidL: number; isProxyOutbid: boolean; endsAt: string };
  PROXY_EXHAUSTED: { auctionId: number; parcelName: string; proxyMaxL: number; endsAt: string };
  AUCTION_WON: { auctionId: number; parcelName: string; winningBidL: number };
  AUCTION_LOST: { auctionId: number; parcelName: string; winningBidL: number };
  AUCTION_ENDED_SOLD: { auctionId: number; parcelName: string; winningBidL: number };
  AUCTION_ENDED_RESERVE_NOT_MET: { auctionId: number; parcelName: string; highestBidL: number };
  AUCTION_ENDED_NO_BIDS: { auctionId: number; parcelName: string };
  AUCTION_ENDED_BOUGHT_NOW: { auctionId: number; parcelName: string; buyNowL: number };
  ESCROW_FUNDED: { auctionId: number; escrowId: number; parcelName: string; transferDeadline: string };
  ESCROW_TRANSFER_CONFIRMED: { auctionId: number; escrowId: number; parcelName: string };
  ESCROW_PAYOUT: { auctionId: number; escrowId: number; parcelName: string; payoutL: number };
  ESCROW_EXPIRED: { auctionId: number; escrowId: number; parcelName: string };
  ESCROW_DISPUTED: { auctionId: number; escrowId: number; parcelName: string; reasonCategory: string };
  ESCROW_FROZEN: { auctionId: number; escrowId: number; parcelName: string; reason: string };
  ESCROW_PAYOUT_STALLED: { auctionId: number; escrowId: number; parcelName: string };
  ESCROW_TRANSFER_REMINDER: { auctionId: number; escrowId: number; parcelName: string; transferDeadline: string };
  LISTING_VERIFIED: { auctionId: number; parcelName: string };
  LISTING_SUSPENDED: { auctionId: number; parcelName: string; reason: string };
  LISTING_REVIEW_REQUIRED: { auctionId: number; parcelName: string; reason: string };
  LISTING_CANCELLED_BY_SELLER: { auctionId: number; parcelName: string; reason: string };
  REVIEW_RECEIVED: { auctionId: number; parcelName: string; reviewId: number; rating: number };
  SYSTEM_ANNOUNCEMENT: Record<string, unknown>;
};
// NOTE: The notification `data` blob keys (auctionId, escrowId, reviewId) are
// stored as numeric Long values by NotificationDataBuilder on the backend and
// have NOT been migrated to publicId UUIDs in this branch. The categoryMap
// deeplinks (/auction/${d.auctionId}) will need to be updated once the backend
// migrates these data keys — tracked as a follow-up item.

export type NotificationData = NotificationDataMap[NotificationCategory];

export interface NotificationDto {
  publicId: string;
  category: NotificationCategory;
  group: NotificationGroup;
  title: string;
  body: string;
  data: Record<string, unknown>;
  read: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface UnreadCountResponse {
  count: number;
  byGroup?: Partial<Record<NotificationGroup, number>>;
}

export type NotificationsEnvelope =
  | { type: "NOTIFICATION_UPSERTED"; isUpdate: boolean; notification: NotificationDto }
  | { type: "READ_STATE_CHANGED" };

export type AccountEnvelope =
  | { type: "PENALTY_CLEARED" };
