import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import { makeWrapper } from "@/test/render";
import { server } from "@/test/msw/server";
import type { SellerAuctionResponse } from "@/types/auction";
import { useActivateAuction } from "./useActivateAuction";

function base(overrides: Partial<SellerAuctionResponse> = {}): SellerAuctionResponse {
  return {
    id: 42,
    sellerId: 1,
    title: "Featured Parcel Listing",
    parcel: {
      id: 1,
      slParcelUuid: "00000000-0000-0000-0000-000000000001",
      ownerUuid: "aaaa1111-0000-0000-0000-000000000000",
      ownerType: "agent",
      regionName: "Heterocera",
      gridX: 1000,
      gridY: 1000,
      positionX: 128,
      positionY: 128,
      positionZ: 0,
      continentName: "Heterocera",
      areaSqm: 1024,
      description: null,
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: false,
      verifiedAt: null,
      lastChecked: null,
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "DRAFT_PAID",
    verificationMethod: null,
    verificationTier: null,
    pendingVerification: null,
    verificationNotes: null,
    startingBid: 500,
    reservePrice: null,
    buyNowPrice: null,
    currentBid: null,
    bidCount: 0,
    currentHighBid: null,
    bidderCount: 0,
    winnerId: null,
    durationHours: 72,
    snipeProtect: true,
    snipeWindowMin: 10,
    startsAt: null,
    endsAt: null,
    originalEndsAt: null,
    sellerDesc: null,
    tags: [],
    photos: [],
    listingFeePaid: true,
    listingFeeAmt: 100,
    listingFeeTxn: null,
    listingFeePaidAt: null,
    commissionRate: 0.05,
    commissionAmt: null,
    createdAt: "2026-04-17T00:00:00Z",
    updatedAt: "2026-04-17T00:00:00Z",
    ...overrides,
  };
}

describe("useActivateAuction", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("fetches the auction and keeps polling while status is non-terminal", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/auctions/42", () => {
        calls += 1;
        return HttpResponse.json(base({ status: "DRAFT_PAID" }));
      }),
    );
    const { result } = renderHook(() => useActivateAuction(42), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });
    await waitFor(() => expect(result.current.data?.status).toBe("DRAFT_PAID"));
    const initialCalls = calls;
    await vi.advanceTimersByTimeAsync(5_100);
    expect(calls).toBeGreaterThan(initialCalls);
  });

  it("stops polling when the auction reaches a polling-stop status", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/auctions/42", () => {
        calls += 1;
        return HttpResponse.json(base({ status: "ACTIVE" }));
      }),
    );
    const { result } = renderHook(() => useActivateAuction(42), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });
    await waitFor(() => expect(result.current.data?.status).toBe("ACTIVE"));
    const initialCalls = calls;
    await vi.advanceTimersByTimeAsync(15_000);
    // Polling must stop once we hit a polling-stop status.
    expect(calls).toBe(initialCalls);
  });
});
