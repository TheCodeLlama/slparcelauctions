import type { BidHistoryEntry } from "@/types/auction";

/**
 * Frozen 4-bid sample dataset used by the seller's DRAFT preview to populate
 * an otherwise-empty bid history and right-rail. The placedAt offsets are
 * computed once at module load — re-renders show stable values until the
 * page is re-mounted, which is fine for a preview surface.
 */
export const SAMPLE_BIDS: ReadonlyArray<BidHistoryEntry> = Object.freeze([
  {
    bidPublicId: "sample-1",
    userPublicId: "sample-bidder-a",
    bidderDisplayName: "Sample Bidder A",
    amount: 1000,
    bidType: "MANUAL",
    snipeExtensionMinutes: null,
    newEndsAt: null,
    createdAt: new Date(Date.now() - 90 * 60_000).toISOString(),
  },
  {
    bidPublicId: "sample-2",
    userPublicId: "sample-bidder-b",
    bidderDisplayName: "Sample Bidder B",
    amount: 1150,
    bidType: "MANUAL",
    snipeExtensionMinutes: null,
    newEndsAt: null,
    createdAt: new Date(Date.now() - 60 * 60_000).toISOString(),
  },
  {
    bidPublicId: "sample-3",
    userPublicId: "sample-bidder-a",
    bidderDisplayName: "Sample Bidder A",
    amount: 1300,
    bidType: "MANUAL",
    snipeExtensionMinutes: null,
    newEndsAt: null,
    createdAt: new Date(Date.now() - 30 * 60_000).toISOString(),
  },
  {
    bidPublicId: "sample-4",
    userPublicId: "sample-bidder-c",
    bidderDisplayName: "Sample Bidder C",
    amount: 1500,
    bidType: "MANUAL",
    snipeExtensionMinutes: null,
    newEndsAt: null,
    createdAt: new Date(Date.now() - 10 * 60_000).toISOString(),
  },
]);

export function sampleCurrentBid(): number {
  return Math.max(...SAMPLE_BIDS.map((b) => b.amount));
}

export function sampleBidderCount(): number {
  return new Set(SAMPLE_BIDS.map((b) => b.userPublicId)).size;
}
