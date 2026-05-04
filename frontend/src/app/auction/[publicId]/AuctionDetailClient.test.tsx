import { describe, expect, it, vi, beforeEach } from "vitest";
import { act } from "react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { fakePublicAuction } from "@/test/fixtures/auction";
import type { Page } from "@/types/page";
import type { BidHistoryEntry } from "@/types/auction";
import { AuctionDetailClient } from "./AuctionDetailClient";

// -------------------------------------------------------------
// WebSocket module mock. Mirrors the EscrowPageClient test pattern —
// we capture the envelope callback and drive AUCTION_CANCELLED into
// it directly instead of running real STOMP / SockJS plumbing.
// -------------------------------------------------------------
const { subscribeMock, subscribeToConnectionStateMock, getConnectionStateMock } =
  vi.hoisted(() => {
    type WsStatus =
      | "disconnected"
      | "connecting"
      | "connected"
      | "reconnecting"
      | "error";
    return {
      subscribeMock: vi.fn(),
      subscribeToConnectionStateMock: vi.fn(),
      getConnectionStateMock: vi.fn(() => ({
        status: "connected" as WsStatus,
      })),
    };
  });

vi.mock("@/lib/ws/client", () => ({
  subscribe: (...args: unknown[]) => subscribeMock(...args),
  subscribeToConnectionState: (
    listener: (state: { status: string }) => void,
  ) => subscribeToConnectionStateMock(listener),
  getConnectionState: getConnectionStateMock,
}));

const emptyBidPage: Page<BidHistoryEntry> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: 20,
};

describe("AuctionDetailClient — AUCTION_CANCELLED envelope", () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    subscribeMock.mockImplementation(() => () => {});
    subscribeToConnectionStateMock.mockReset();
    subscribeToConnectionStateMock.mockImplementation((listener) => {
      listener({ status: "connected" });
      return () => {};
    });
    getConnectionStateMock.mockReset();
    getConnectionStateMock.mockReturnValue({ status: "connected" });
  });

  it("invalidates the auction + bid history queries when AUCTION_CANCELLED arrives", async () => {
    const initialAuction = fakePublicAuction({
      publicId: "00000000-0000-0000-0000-000000000007",
      status: "ACTIVE",
      seller: {
        publicId: "00000000-0000-0000-0000-00000000002a",
        displayName: "Seller",
        avatarUrl: null,
        averageRating: null,
        reviewCount: null,
        completedSales: 0,
        completionRate: null,
        memberSince: null,
      },
    });

    let auctionFetchCount = 0;
    let bidHistoryFetchCount = 0;
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007", () => {
        auctionFetchCount += 1;
        // Keep the auction ACTIVE on the refetch so the page does not
        // mount AuctionEndedPanel — the public DTO collapses CANCELLED
        // to ENDED but the panel requires a populated endOutcome to
        // render. The test's contract is "the invalidation fires", not
        // "the page transitions" — both are observed by the fetch
        // counter incrementing.
        return HttpResponse.json(initialAuction);
      }),
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/bids", () => {
        bidHistoryFetchCount += 1;
        return HttpResponse.json(emptyBidPage);
      }),
    );

    renderWithProviders(
      <AuctionDetailClient
        initialAuction={initialAuction}
        initialBidPage={emptyBidPage}
      />,
      { auth: "anonymous" },
    );

    // Drive the envelope through the captured handler.
    const handler = subscribeMock.mock.calls[0]?.[1] as (
      env: unknown,
    ) => void;
    expect(handler).toBeDefined();

    const initialAuctionFetches = auctionFetchCount;
    const initialBidFetches = bidHistoryFetchCount;

    act(() => {
      handler({
        type: "AUCTION_CANCELLED",
        auctionId: 7,
        cancelledAt: "2026-04-25T12:00:00Z",
        hadBids: true,
      });
    });

    // The handler invalidates ["auction", id] + ["bids", id, 0] +
    // myProxy. The first two refetch via MSW; assert their counts
    // increased.
    await waitFor(() => {
      expect(auctionFetchCount).toBeGreaterThan(initialAuctionFetches);
    });
    await waitFor(() => {
      expect(bidHistoryFetchCount).toBeGreaterThan(initialBidFetches);
    });
  });
});
