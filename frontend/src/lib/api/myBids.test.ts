import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import type { Page } from "@/types/page";
import type { MyBidSummary } from "@/types/auction";
import { getMyBids } from "./myBids";

function emptyPage<T>(): Page<T> {
  return {
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size: 20,
  };
}

function mySummary(overrides: Partial<MyBidSummary> = {}): MyBidSummary {
  return {
    auction: {
      id: 1,
      status: "ACTIVE",
      endOutcome: null,
      parcelName: "Heterocera 128/128",
      parcelRegion: "Heterocera",
      parcelAreaSqm: 1024,
      snapshotUrl: null,
      endsAt: "2026-04-22T00:00:00Z",
      endedAt: null,
      currentBid: 2500,
      bidderCount: 3,
      sellerUserId: 100,
      sellerDisplayName: "Seller",
    },
    myHighestBidAmount: 2500,
    myHighestBidAt: "2026-04-20T12:00:00Z",
    myProxyMaxAmount: null,
    myBidStatus: "WINNING",
    ...overrides,
  };
}

describe("getMyBids", () => {
  it("sends page=0&size=20 and omits status when no args are passed", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/me/bids", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(emptyPage<MyBidSummary>());
      }),
    );

    await getMyBids();

    expect(captured!.pathname).toBe("/api/v1/users/me/bids");
    expect(captured!.searchParams.get("page")).toBe("0");
    expect(captured!.searchParams.get("size")).toBe("20");
    // Undefined status is stripped by the api helper.
    expect(captured!.searchParams.has("status")).toBe(false);
  });

  it("sends status=active with default page/size when filter=active", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/me/bids", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(emptyPage<MyBidSummary>());
      }),
    );

    await getMyBids({ status: "active" });

    expect(captured!.searchParams.get("status")).toBe("active");
    expect(captured!.searchParams.get("page")).toBe("0");
    expect(captured!.searchParams.get("size")).toBe("20");
  });

  it("honours explicit page overrides alongside status", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/me/bids", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json({
          ...emptyPage<MyBidSummary>(),
          content: [mySummary({ myBidStatus: "WON" })],
          totalElements: 1,
        });
      }),
    );

    const result = await getMyBids({ status: "won", page: 2 });

    expect(captured!.searchParams.get("status")).toBe("won");
    expect(captured!.searchParams.get("page")).toBe("2");
    expect(captured!.searchParams.get("size")).toBe("20");
    expect(result.content[0].myBidStatus).toBe("WON");
  });
});
