import { describe, it, expect } from "vitest";
import { Bell } from "@/components/ui/icons";
import { categoryMap, categoryConfigOrFallback } from "./categoryMap";
import type { NotificationCategory } from "./types";

const ALL_CATEGORIES: NotificationCategory[] = [
  "OUTBID", "PROXY_EXHAUSTED",
  "AUCTION_WON", "AUCTION_LOST",
  "AUCTION_ENDED_SOLD", "AUCTION_ENDED_RESERVE_NOT_MET",
  "AUCTION_ENDED_NO_BIDS", "AUCTION_ENDED_BOUGHT_NOW",
  "ESCROW_FUNDED", "ESCROW_TRANSFER_CONFIRMED",
  "ESCROW_PAYOUT", "ESCROW_EXPIRED", "ESCROW_DISPUTED",
  "ESCROW_FROZEN", "ESCROW_PAYOUT_STALLED", "ESCROW_TRANSFER_REMINDER",
  "LISTING_VERIFIED", "LISTING_SUSPENDED",
  "LISTING_REVIEW_REQUIRED", "LISTING_CANCELLED_BY_SELLER",
  "REVIEW_RECEIVED", "SYSTEM_ANNOUNCEMENT",
];

describe("categoryMap", () => {
  it("covers all 22 notification categories", () => {
    expect(Object.keys(categoryMap)).toHaveLength(22);
    for (const cat of ALL_CATEGORIES) {
      expect(categoryMap).toHaveProperty(cat);
    }
  });

  it("every entry has required fields", () => {
    for (const [cat, entry] of Object.entries(categoryMap)) {
      expect(entry.group, cat).toBeTruthy();
      expect(entry.icon, cat).toBeTruthy();
      expect(entry.iconBgClass, cat).toBeTruthy();
      expect(["success", "error", "warning", "info"]).toContain(entry.toastVariant);
      expect(typeof entry.deeplink).toBe("function");
    }
  });

  it("deeplink returns a string for representative categories", () => {
    const data = { auctionId: 42, escrowId: 7 };
    expect(categoryMap.OUTBID.deeplink(data)).toBe("/auction/42");
    expect(categoryMap.ESCROW_FUNDED.deeplink(data)).toBe("/auction/42/escrow");
    expect(categoryMap.LISTING_SUSPENDED.deeplink(data)).toBe("/dashboard/listings");
    expect(categoryMap.SYSTEM_ANNOUNCEMENT.deeplink(data)).toBe("/notifications");
  });
});

describe("categoryConfigOrFallback", () => {
  it("returns the mapped entry for known categories", () => {
    const entry = categoryConfigOrFallback("OUTBID");
    expect(entry.group).toBe("bidding");
    expect(entry.toastVariant).toBe("warning");
  });

  it("returns bell + info fallback for unknown category", () => {
    const entry = categoryConfigOrFallback("SOME_FUTURE_CATEGORY");
    expect(entry.icon).toBe(Bell);
    expect(entry.toastVariant).toBe("info");
    expect(entry.group).toBe("system");
    expect(entry.deeplink({})).toBe("/notifications");
  });
});
