import { describe, it, expect } from "vitest";
import { Bell } from "@/components/ui/icons";
import { categoryMap, categoryConfigOrFallback } from "./categoryMap";
import type { NotificationCategory } from "./types";

const ALL_CATEGORIES: NotificationCategory[] = [
  "OUTBID", "PROXY_EXHAUSTED",
  "AUCTION_WON", "AUCTION_LOST",
  "AUCTION_ENDED_SOLD", "AUCTION_ENDED_RESERVE_NOT_MET",
  "AUCTION_ENDED_NO_BIDS", "AUCTION_ENDED_BOUGHT_NOW",
  "ESCROW_FUNDED", "ESCROW_SELL_TO_SET", "ESCROW_TRANSFER_CONFIRMED",
  "ESCROW_PAYOUT", "ESCROW_EXPIRED", "ESCROW_DISPUTED",
  "ESCROW_FROZEN", "ESCROW_PAYOUT_STALLED", "ESCROW_TRANSFER_REMINDER",
  "LISTING_VERIFIED", "LISTING_SUSPENDED",
  "LISTING_REVIEW_REQUIRED", "LISTING_CANCELLED_BY_SELLER",
  "LISTING_CANCELLED_DURING_ESCROW",
  "REVIEW_RECEIVED",
  "SUPPORT_TICKET_ADMIN_REPLIED", "SUPPORT_TICKET_RESOLVED",
  "SUPPORT_TICKET_OPENED", "SUPPORT_TICKET_USER_REPLIED",
  "SYSTEM_ANNOUNCEMENT",
];

describe("categoryMap", () => {
  it("covers all 28 notification categories", () => {
    expect(Object.keys(categoryMap)).toHaveLength(28);
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

  it("deeplink prefers auctionPublicId (UUID) for auction routes", () => {
    const data = {
      auctionId: 42,
      auctionPublicId: "00000000-0000-0000-0000-0000000002a",
      escrowId: 7,
    };
    expect(categoryMap.OUTBID.deeplink(data)).toBe(
      "/auction/00000000-0000-0000-0000-0000000002a",
    );
    expect(categoryMap.ESCROW_FUNDED.deeplink(data)).toBe(
      "/auction/00000000-0000-0000-0000-0000000002a/escrow",
    );
    expect(categoryMap.LISTING_CANCELLED_DURING_ESCROW.deeplink(data)).toBe(
      "/auction/00000000-0000-0000-0000-0000000002a/escrow",
    );
    expect(categoryMap.LISTING_SUSPENDED.deeplink(data)).toBe("/dashboard/listings");
    expect(categoryMap.SYSTEM_ANNOUNCEMENT.deeplink(data)).toBe("/notifications");
  });

  it("ESCROW_SELL_TO_SET deeplinks + action route the buyer to the escrow page", () => {
    const data = {
      auctionId: 42,
      auctionPublicId: "00000000-0000-0000-0000-0000000002a",
      escrowId: 7,
    };
    const entry = categoryMap.ESCROW_SELL_TO_SET;
    expect(entry.group).toBe("escrow");
    expect(entry.toastVariant).toBe("info");
    expect(entry.deeplink(data)).toBe(
      "/auction/00000000-0000-0000-0000-0000000002a/escrow",
    );
    expect(entry.action?.label).toBe("Buy parcel");
    expect(entry.action?.href(data)).toBe(
      "/auction/00000000-0000-0000-0000-0000000002a/escrow",
    );
  });

  it("SUPPORT_TICKET_ADMIN_REPLIED deeplinks + action route the user to /support/{ticketPublicId}", () => {
    const data = {
      ticketPublicId: "00000000-0000-0000-0000-000000000abc",
      subject: "Stuck on escrow",
      adminDisplayName: "Sara",
    };
    const entry = categoryMap.SUPPORT_TICKET_ADMIN_REPLIED;
    expect(entry.group).toBe("system");
    expect(entry.toastVariant).toBe("info");
    expect(entry.deeplink(data)).toBe(
      "/support/00000000-0000-0000-0000-000000000abc",
    );
    expect(entry.action?.label).toBe("View ticket");
    expect(entry.action?.href(data)).toBe(
      "/support/00000000-0000-0000-0000-000000000abc",
    );
  });

  it("SUPPORT_TICKET_RESOLVED deeplinks the user to /support/{ticketPublicId} with success styling", () => {
    const data = {
      ticketPublicId: "00000000-0000-0000-0000-000000000abc",
      subject: "Stuck on escrow",
    };
    const entry = categoryMap.SUPPORT_TICKET_RESOLVED;
    expect(entry.group).toBe("system");
    expect(entry.toastVariant).toBe("success");
    expect(entry.deeplink(data)).toBe(
      "/support/00000000-0000-0000-0000-000000000abc",
    );
    expect(entry.action).toBeUndefined();
  });

  it("SUPPORT_TICKET_OPENED deeplinks + action route the admin to /admin/support/{ticketPublicId}", () => {
    const data = {
      ticketPublicId: "00000000-0000-0000-0000-000000000abc",
      subject: "Stuck on escrow",
      submitterDisplayName: "buyer.resident",
      category: "BIDDING",
    };
    const entry = categoryMap.SUPPORT_TICKET_OPENED;
    expect(entry.group).toBe("system");
    expect(entry.toastVariant).toBe("info");
    expect(entry.deeplink(data)).toBe(
      "/admin/support/00000000-0000-0000-0000-000000000abc",
    );
    expect(entry.action?.label).toBe("Open queue");
    expect(entry.action?.href(data)).toBe(
      "/admin/support/00000000-0000-0000-0000-000000000abc",
    );
  });

  it("SUPPORT_TICKET_USER_REPLIED deeplinks + action route the admin to /admin/support/{ticketPublicId}", () => {
    const data = {
      ticketPublicId: "00000000-0000-0000-0000-000000000abc",
      subject: "Stuck on escrow",
      submitterDisplayName: "buyer.resident",
    };
    const entry = categoryMap.SUPPORT_TICKET_USER_REPLIED;
    expect(entry.group).toBe("system");
    expect(entry.toastVariant).toBe("info");
    expect(entry.deeplink(data)).toBe(
      "/admin/support/00000000-0000-0000-0000-000000000abc",
    );
    expect(entry.action?.label).toBe("Open ticket");
    expect(entry.action?.href(data)).toBe(
      "/admin/support/00000000-0000-0000-0000-000000000abc",
    );
  });

  it("deeplink falls back to auctionId when auctionPublicId is absent (legacy rows)", () => {
    // Notification rows persisted before the publicId backfill carry only
    // the internal Long auctionId; the resulting URL routes to a 404 but
    // we prefer that over a NullPointerException at click time.
    const data = { auctionId: 42, escrowId: 7 };
    expect(categoryMap.OUTBID.deeplink(data)).toBe("/auction/42");
    expect(categoryMap.ESCROW_FUNDED.deeplink(data)).toBe("/auction/42/escrow");
    expect(categoryMap.LISTING_CANCELLED_DURING_ESCROW.deeplink(data)).toBe(
      "/auction/42/escrow",
    );
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
