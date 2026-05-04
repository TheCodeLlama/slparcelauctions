import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import type { Page } from "@/types/page";
import type {
  BidHistoryEntry,
  BidResponse,
  ProxyBidResponse,
  PublicAuctionResponse,
} from "@/types/auction";
import {
  cancelProxy,
  createProxy,
  getActiveListingsForUser,
  getBidHistory,
  getMyProxy,
  placeBid,
  updateProxy,
} from "./auctions";

// ---------- Fixture builders ----------

function bidHistoryEntry(
  overrides: Partial<BidHistoryEntry> = {},
): BidHistoryEntry {
  return {
    bidPublicId: "00000000-0000-0000-0000-000000000001",
    userPublicId: "00000000-0000-0000-0000-00000000002a",
    bidderDisplayName: "Alice",
    amount: 1500,
    bidType: "MANUAL",
    snipeExtensionMinutes: null,
    newEndsAt: null,
    createdAt: "2026-04-20T12:00:00Z",
    ...overrides,
  };
}

function pageOf<T>(content: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 20,
    ...overrides,
  };
}

function bidResponse(overrides: Partial<BidResponse> = {}): BidResponse {
  return {
    bidPublicId: "00000000-0000-0000-0000-00000000000a",
    auctionPublicId: "00000000-0000-0000-0000-000000000007",
    amount: 2000,
    bidType: "MANUAL",
    bidCount: 3,
    endsAt: "2026-04-21T00:00:00Z",
    originalEndsAt: "2026-04-21T00:00:00Z",
    snipeExtensionMinutes: null,
    newEndsAt: null,
    buyNowTriggered: false,
    ...overrides,
  };
}

function proxyBidResponse(
  overrides: Partial<ProxyBidResponse> = {},
): ProxyBidResponse {
  return {
    proxyBidPublicId: "00000000-0000-0000-0000-000000000063",
    auctionPublicId: "00000000-0000-0000-0000-000000000007",
    maxAmount: 5000,
    status: "ACTIVE",
    createdAt: "2026-04-20T12:00:00Z",
    updatedAt: "2026-04-20T12:00:00Z",
    ...overrides,
  };
}

function publicAuction(
  overrides: Partial<PublicAuctionResponse> = {},
): PublicAuctionResponse {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    sellerPublicId: "00000000-0000-0000-0000-000000000064",
    title: "Featured Parcel Listing",
    parcel: {
      id: 1,
      slParcelUuid: "00000000-0000-0000-0000-000000000001",
      ownerUuid: "aaaa1111-0000-0000-0000-000000000000",
      ownerType: "agent",
      regionName: "Heterocera",
      gridX: 0,
      gridY: 0,
      positionX: 128,
      positionY: 128,
      positionZ: 0,
      ownerName: null,
      parcelName: null,
      continentName: null,
      areaSqm: 1024,
      description: null,
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: true,
      verifiedAt: "2026-04-17T00:00:00Z",
      lastChecked: "2026-04-17T00:00:00Z",
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "ACTIVE",
    verificationTier: "SCRIPT",
    startingBid: 500,
    hasReserve: false,
    reserveMet: true,
    buyNowPrice: null,
    currentBid: 1500,
    bidCount: 2,
    currentHighBid: 1500,
    bidderCount: 2,
    durationHours: 72,
    snipeProtect: true,
    snipeWindowMin: 15,
    startsAt: "2026-04-17T00:00:00Z",
    endsAt: "2026-04-20T00:00:00Z",
    originalEndsAt: "2026-04-20T00:00:00Z",
    sellerDesc: null,
    tags: [],
    photos: [],
    ...overrides,
  };
}

// ---------- getBidHistory ----------

describe("getBidHistory", () => {
  it("sends page=0&size=20 by default and returns the parsed Page", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/auctions/:id/bids", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(
          pageOf([
            bidHistoryEntry({
              bidPublicId: "00000000-0000-0000-0000-000000000001",
            }),
          ]),
        );
      }),
    );

    const page = await getBidHistory(42);

    expect(captured).not.toBeNull();
    expect(captured!.pathname).toBe("/api/v1/auctions/42/bids");
    expect(captured!.searchParams.get("page")).toBe("0");
    expect(captured!.searchParams.get("size")).toBe("20");
    expect(page.content).toHaveLength(1);
    expect(page.content[0].bidPublicId).toBe("00000000-0000-0000-0000-000000000001");
  });

  it("honours explicit page and size overrides", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/auctions/:id/bids", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(pageOf([], { number: 3, size: 5 }));
      }),
    );

    await getBidHistory(1, { page: 3, size: 5 });

    expect(captured!.searchParams.get("page")).toBe("3");
    expect(captured!.searchParams.get("size")).toBe("5");
  });
});

// ---------- placeBid ----------

describe("placeBid", () => {
  it("POSTs { amount } and returns the parsed BidResponse", async () => {
    let capturedBody: unknown = null;
    let capturedMethod = "";
    server.use(
      http.post("*/api/v1/auctions/:id/bids", async ({ request }) => {
        capturedMethod = request.method;
        capturedBody = await request.json();
        return HttpResponse.json(bidResponse({ amount: 2500 }), { status: 201 });
      }),
    );

    const result = await placeBid(7, 2500);

    expect(capturedMethod).toBe("POST");
    expect(capturedBody).toEqual({ amount: 2500 });
    expect(result.amount).toBe(2500);
    expect(result.buyNowTriggered).toBe(false);
  });
});

// ---------- createProxy ----------

describe("createProxy", () => {
  it("POSTs { maxAmount } and returns the parsed ProxyBidResponse", async () => {
    let capturedBody: unknown = null;
    let capturedMethod = "";
    server.use(
      http.post("*/api/v1/auctions/:id/proxy-bid", async ({ request }) => {
        capturedMethod = request.method;
        capturedBody = await request.json();
        return HttpResponse.json(proxyBidResponse({ maxAmount: 8000 }), {
          status: 201,
        });
      }),
    );

    const result = await createProxy(7, 8000);

    expect(capturedMethod).toBe("POST");
    expect(capturedBody).toEqual({ maxAmount: 8000 });
    expect(result.maxAmount).toBe(8000);
    expect(result.status).toBe("ACTIVE");
  });
});

// ---------- updateProxy ----------

describe("updateProxy", () => {
  it("PUTs { maxAmount } and returns the updated ProxyBidResponse", async () => {
    let capturedBody: unknown = null;
    let capturedMethod = "";
    server.use(
      http.put("*/api/v1/auctions/:id/proxy-bid", async ({ request }) => {
        capturedMethod = request.method;
        capturedBody = await request.json();
        return HttpResponse.json(proxyBidResponse({ maxAmount: 12000 }));
      }),
    );

    const result = await updateProxy(7, 12000);

    expect(capturedMethod).toBe("PUT");
    expect(capturedBody).toEqual({ maxAmount: 12000 });
    expect(result.maxAmount).toBe(12000);
  });
});

// ---------- cancelProxy ----------

describe("cancelProxy", () => {
  it("DELETEs the proxy-bid endpoint and returns void on 204", async () => {
    let capturedMethod = "";
    let capturedPath = "";
    server.use(
      http.delete("*/api/v1/auctions/:id/proxy-bid", ({ request }) => {
        capturedMethod = request.method;
        capturedPath = new URL(request.url).pathname;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const result = await cancelProxy(7);

    expect(capturedMethod).toBe("DELETE");
    expect(capturedPath).toBe("/api/v1/auctions/7/proxy-bid");
    expect(result).toBeUndefined();
  });
});

// ---------- getMyProxy ----------

describe("getMyProxy", () => {
  it("returns the ProxyBidResponse on a 200", async () => {
    server.use(
      http.get("*/api/v1/auctions/:id/proxy-bid", () =>
        HttpResponse.json(proxyBidResponse({ maxAmount: 9999 })),
      ),
    );

    const result = await getMyProxy(7);
    expect(result).not.toBeNull();
    expect(result!.maxAmount).toBe(9999);
  });

  it("returns null on a 404 instead of throwing", async () => {
    server.use(
      http.get("*/api/v1/auctions/:id/proxy-bid", () =>
        HttpResponse.json(
          { status: 404, title: "Proxy bid not found" },
          { status: 404 },
        ),
      ),
    );

    const result = await getMyProxy(7);
    expect(result).toBeNull();
  });

  it("re-throws non-404 errors as ApiError", async () => {
    server.use(
      http.get("*/api/v1/auctions/:id/proxy-bid", () =>
        HttpResponse.json(
          { status: 500, title: "Internal Server Error" },
          { status: 500 },
        ),
      ),
    );

    await expect(getMyProxy(7)).rejects.toMatchObject({ status: 500 });
  });
});

// ---------- getActiveListingsForUser ----------

describe("getActiveListingsForUser", () => {
  it("sends status=ACTIVE, default size=6, and page=0", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/:userId/auctions", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(
          pageOf([publicAuction()], { size: 6, totalElements: 1 }),
        );
      }),
    );

    const page = await getActiveListingsForUser(100);

    expect(captured!.pathname).toBe("/api/v1/users/100/auctions");
    expect(captured!.searchParams.get("status")).toBe("ACTIVE");
    expect(captured!.searchParams.get("page")).toBe("0");
    expect(captured!.searchParams.get("size")).toBe("6");
    expect(page.content).toHaveLength(1);
    expect(page.content[0].status).toBe("ACTIVE");
  });

  it("honours explicit page and size overrides", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/:userId/auctions", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(pageOf<PublicAuctionResponse>([]));
      }),
    );

    await getActiveListingsForUser(100, { page: 2, size: 12 });

    expect(captured!.searchParams.get("page")).toBe("2");
    expect(captured!.searchParams.get("size")).toBe("12");
    expect(captured!.searchParams.get("status")).toBe("ACTIVE");
  });
});
