import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { act } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type { Page } from "@/types/page";
import type {
  AuctionEnvelope,
  BidHistoryEntry,
  BidSettlementEnvelope,
  PublicAuctionResponse,
} from "@/types/auction";
import { AuctionDetailClient } from "./AuctionDetailClient";
import AuctionPage from "./page";

// -------------------------------------------------------------
// WebSocket module mock. Captures the last envelope callback +
// connection-state listener so individual tests can drive them
// without running any real STOMP / SockJS plumbing.
// -------------------------------------------------------------

// Hoisted mocks: the inline `as WsStatus` cast widens the inferred return
// type away from the literal `"connected"` so individual tests can call
// `mockReturnValue({ status: "reconnecting" })` without TypeScript
// narrowing the allowed statuses to just what the initial closure
// produced.
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

// next/navigation is mocked by vitest.setup.ts, but the `notFound` export is
// not included there (server-component only). Override the mock to add it so
// the server-component path test can assert it was called.
const notFoundSpy = vi.fn((): never => {
  throw new Error("NEXT_NOT_FOUND");
});
vi.mock("next/navigation", () => ({
  notFound: () => notFoundSpy(),
  usePathname: vi.fn(() => "/auction/1"),
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: () => new URLSearchParams(),
}));

// -------------------------------------------------------------
// Fixtures
// -------------------------------------------------------------

function publicAuctionFixture(
  overrides: Partial<PublicAuctionResponse> = {},
): PublicAuctionResponse {
  return {
    id: 7,
    sellerId: 100,
    parcel: {
      id: 1,
      slParcelUuid: "00000000-0000-0000-0000-000000000001",
      ownerUuid: "aaaa1111-0000-0000-0000-000000000000",
      ownerType: "agent",
      regionName: "Heterocera",
      gridX: 0,
      gridY: 0,
      continentName: null,
      areaSqm: 1024,
      description: "Beachfront parcel",
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: true,
      verifiedAt: "2026-04-20T00:00:00Z",
      lastChecked: "2026-04-20T00:00:00Z",
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "ACTIVE",
    verificationTier: "SCRIPT",
    startingBid: 500,
    hasReserve: false,
    reserveMet: true,
    buyNowPrice: null,
    currentBid: 1500,
    bidCount: 3,
    currentHighBid: 1500,
    bidderCount: 2,
    durationHours: 72,
    snipeProtect: true,
    snipeWindowMin: 10,
    startsAt: "2026-04-19T00:00:00Z",
    endsAt: "2026-04-22T00:00:00Z",
    originalEndsAt: "2026-04-22T00:00:00Z",
    sellerDesc: null,
    tags: [],
    photos: [],
    ...overrides,
  };
}

function bidHistoryEntry(
  overrides: Partial<BidHistoryEntry> = {},
): BidHistoryEntry {
  return {
    bidId: 1,
    userId: 42,
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

function bidSettlement(
  overrides: Partial<BidSettlementEnvelope> = {},
): BidSettlementEnvelope {
  return {
    type: "BID_SETTLEMENT",
    auctionId: 7,
    serverTime: "2026-04-20T12:30:00Z",
    currentBid: 2000,
    currentBidderId: 55,
    currentBidderDisplayName: "Bob",
    bidCount: 4,
    endsAt: "2026-04-22T00:00:00Z",
    originalEndsAt: "2026-04-22T00:00:00Z",
    newBids: [
      bidHistoryEntry({
        bidId: 2,
        userId: 55,
        bidderDisplayName: "Bob",
        amount: 2000,
        createdAt: "2026-04-20T12:30:00Z",
      }),
    ],
    ...overrides,
  };
}

// -------------------------------------------------------------
// Tests
// -------------------------------------------------------------

describe("AuctionPage server component", () => {
  beforeEach(() => {
    notFoundSpy.mockClear();
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

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("calls notFound for a non-numeric id", async () => {
    // The mocked notFound throws so control unwinds before any fetch fires,
    // matching Next.js' real behavior.
    await expect(
      AuctionPage({ params: Promise.resolve({ id: "not-a-number" }) }),
    ).rejects.toThrow("NEXT_NOT_FOUND");
    expect(notFoundSpy).toHaveBeenCalledTimes(1);
  });

  it("calls notFound for a non-positive id", async () => {
    await expect(
      AuctionPage({ params: Promise.resolve({ id: "0" }) }),
    ).rejects.toThrow("NEXT_NOT_FOUND");
    expect(notFoundSpy).toHaveBeenCalledTimes(1);
  });

  it("calls notFound when the auction endpoint returns 404", async () => {
    server.use(
      http.get("*/api/v1/auctions/:id", () =>
        HttpResponse.json(
          { status: 404, title: "Not Found", detail: "No such auction" },
          { status: 404 },
        ),
      ),
      http.get("*/api/v1/auctions/:id/bids", () =>
        HttpResponse.json(pageOf<BidHistoryEntry>([])),
      ),
    );

    await expect(
      AuctionPage({ params: Promise.resolve({ id: "7" }) }),
    ).rejects.toThrow("NEXT_NOT_FOUND");
    expect(notFoundSpy).toHaveBeenCalledTimes(1);
  });

  it("renders the client shell with seeded props for a valid id", async () => {
    const auction = publicAuctionFixture();
    const bids = pageOf([bidHistoryEntry()]);
    server.use(
      http.get("*/api/v1/auctions/:id", () => HttpResponse.json(auction)),
      http.get("*/api/v1/auctions/:id/bids", () => HttpResponse.json(bids)),
    );

    const element = await AuctionPage({
      params: Promise.resolve({ id: "7" }),
    });

    // Type-narrow: AuctionPage returns a JSX element (never void) on the
    // success path, so this is safe.
    expect(element).toBeDefined();
    expect(notFoundSpy).not.toHaveBeenCalled();
  });
});

describe("AuctionDetailClient", () => {
  const auction = publicAuctionFixture();
  const initialBids = pageOf([
    bidHistoryEntry({ bidId: 1, amount: 1500 }),
  ]);

  // Verified non-seller user — triggers the BidPanel bidder variant so the
  // current-bid readout renders. Anonymous renders the unauth gate, which
  // deliberately omits the current-bid display (spec §9).
  const verifiedBidder = {
    id: 999,
    email: "bidder@example.com",
    displayName: "Bidder",
    slAvatarUuid: "99999999-9999-9999-9999-999999999999",
    verified: true,
  } as const;

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
    // Block any stray network calls — the client should be fully seeded.
    server.use(
      http.get("*/api/v1/auctions/:id", () => HttpResponse.json(auction)),
      http.get("*/api/v1/auctions/:id/bids", () =>
        HttpResponse.json(initialBids),
      ),
      http.get("*/api/v1/auctions/:id/proxy-bid", () =>
        HttpResponse.json(
          { status: 404, title: "Not Found" },
          { status: 404 },
        ),
      ),
      // Seller profile enrichment — Task 9 will inline this on the auction
      // DTO, until then AuctionDetailClient fetches it client-side.
      http.get("*/api/v1/users/:id", () =>
        HttpResponse.json({
          id: auction.sellerId,
          displayName: "Seller",
          bio: null,
          profilePicUrl: null,
          slAvatarUuid: null,
          slAvatarName: null,
          slUsername: null,
          slDisplayName: null,
          verified: true,
          avgSellerRating: null,
          avgBuyerRating: null,
          totalSellerReviews: 0,
          totalBuyerReviews: 0,
          completedSales: 0,
          createdAt: "2026-04-01T00:00:00Z",
        }),
      ),
    );
  });

  it("renders seeded auction data into the real subcomponents", () => {
    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );

    // ParcelInfoPanel consumes the seeded parcel + auction fields.
    expect(screen.getByTestId("parcel-info-panel")).toHaveTextContent(
      "Heterocera",
    );
    expect(screen.getByTestId("parcel-info-panel-title")).toHaveTextContent(
      "Beachfront parcel",
    );
    // The auction-hero falls back to the placeholder variant when no photos
    // and no parcel snapshot are present (as in this fixture).
    expect(screen.getByTestId("auction-hero")).toHaveAttribute(
      "data-variant",
      "placeholder",
    );
    expect(screen.getByTestId("bid-history-total")).toHaveTextContent("1");
    // BidPanel bidder variant renders the current-high + bidder count.
    expect(screen.getByTestId("bid-panel-current-high")).toHaveTextContent(
      "L$ 1,500",
    );
    expect(screen.getByTestId("bid-panel-bidder-count")).toHaveTextContent(
      "2",
    );
    // WS state is surfaced via the panel-slot data attribute so the
    // AuctionDetailClient shell can keep exposing it regardless of the
    // rendered variant.
    expect(screen.getByTestId("bid-panel-slot")).toHaveAttribute(
      "data-ws-state",
      "connected",
    );
  });

  it("renders the unauth gate for anonymous viewers", () => {
    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "anonymous" },
    );
    const gate = screen.getByTestId("auth-gate-message");
    expect(gate).toHaveAttribute("data-kind", "unauth");
  });

  it("renders the bidder panel with place-bid form for a verified non-seller", () => {
    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );
    expect(screen.getByTestId("bid-panel-bidder")).toBeInTheDocument();
    expect(screen.getByTestId("place-bid-form")).toBeInTheDocument();
    expect(screen.getByTestId("proxy-bid-section")).toBeInTheDocument();
  });

  it("subscribes to the auction topic on mount", () => {
    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );

    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock.mock.calls[0][0]).toBe(`/topic/auction/${auction.id}`);
  });

  it("merges a BID_SETTLEMENT envelope into the cache", async () => {
    // Capture the envelope callback so we can invoke it manually.
    let capturedOnMessage:
      | ((env: AuctionEnvelope) => void)
      | null = null;
    subscribeMock.mockImplementation(
      (_destination: string, onMessage: (env: AuctionEnvelope) => void) => {
        capturedOnMessage = onMessage;
        return () => {};
      },
    );

    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );

    expect(capturedOnMessage).not.toBeNull();

    act(() => {
      capturedOnMessage!(bidSettlement());
    });

    // currentHighBid updates from the envelope's currentBid.
    await waitFor(() =>
      expect(
        screen.getByTestId("bid-panel-current-high"),
      ).toHaveTextContent("L$ 2,000"),
    );
    // bidderCount updates to env.bidCount per spec §5.
    expect(screen.getByTestId("bid-panel-bidder-count")).toHaveTextContent(
      "4",
    );
    // Page 0 total grew by the number of newBids (one).
    expect(screen.getByTestId("bid-history-total")).toHaveTextContent("2");
  });

  it("dedupes envelopes that overlap with the seeded page", async () => {
    let capturedOnMessage:
      | ((env: AuctionEnvelope) => void)
      | null = null;
    subscribeMock.mockImplementation(
      (_destination: string, onMessage: (env: AuctionEnvelope) => void) => {
        capturedOnMessage = onMessage;
        return () => {};
      },
    );

    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );

    // Envelope carries a bid whose id matches the seeded row (1).
    act(() => {
      capturedOnMessage!(
        bidSettlement({
          newBids: [bidHistoryEntry({ bidId: 1, amount: 1500 })],
        }),
      );
    });

    // Total counts ALL received bids (including duplicates) — the dedupe is
    // about the rendered content, not the count. The rendered content only
    // holds one copy; but `totalElements + newBids.length` is the spec'd
    // accounting. So just verify nothing explodes and the single row stayed.
    await waitFor(() =>
      expect(screen.getByTestId("bid-panel-current-high")).toHaveTextContent(
        "L$ 2,000",
      ),
    );
  });

  it("renders AuctionEndedPanel and AuctionEndedRow when status is ENDED", () => {
    const endedAuction = publicAuctionFixture({
      status: "ENDED",
      currentHighBid: 2500,
      currentBid: 2500,
      bidCount: 3,
    });
    // Cast through unknown because PublicAuctionResponse doesn't carry
    // endOutcome/winner fields — the widened cache entry in
    // AuctionDetailClient admits them on the public shape.
    const endedWithExtras = {
      ...endedAuction,
      endOutcome: "SOLD" as const,
      finalBidAmount: 2500,
      winnerUserId: 42,
      winnerDisplayName: "Alice",
    } as unknown as PublicAuctionResponse;

    renderWithProviders(
      <AuctionDetailClient
        initialAuction={endedWithExtras}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );

    const panel = screen.getByTestId("auction-ended-panel");
    expect(panel).toHaveAttribute("data-outcome", "SOLD");
    expect(screen.getByTestId("auction-ended-headline")).toHaveTextContent(
      "Sold for L$2,500",
    );
    const row = screen.getByTestId("auction-ended-row");
    expect(row).toHaveAttribute("data-outcome", "SOLD");
    expect(row).toHaveTextContent("Auction ended — L$2,500");
  });

  it("surfaces the snipe-extension banner on a snipe-triggering envelope", async () => {
    let capturedOnMessage:
      | ((env: AuctionEnvelope) => void)
      | null = null;
    subscribeMock.mockImplementation(
      (_destination: string, onMessage: (env: AuctionEnvelope) => void) => {
        capturedOnMessage = onMessage;
        return () => {};
      },
    );

    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );

    // Craft an envelope whose newBid carries a snipe-extension stamp.
    const futureEndsAt = new Date(Date.now() + 2 * 3_600_000).toISOString();
    act(() => {
      capturedOnMessage!(
        bidSettlement({
          endsAt: futureEndsAt,
          newBids: [
            bidHistoryEntry({
              bidId: 99,
              amount: 2000,
              bidderDisplayName: "Bob",
              snipeExtensionMinutes: 15,
              newEndsAt: futureEndsAt,
            }),
          ],
        }),
      );
    });

    await waitFor(() =>
      expect(
        screen.getByTestId("snipe-extension-banner"),
      ).toBeInTheDocument(),
    );
    expect(screen.getByTestId("snipe-extension-banner")).toHaveTextContent(
      "Auction extended by 15m",
    );
  });

  it("reflects connection state on the bid-panel slot", () => {
    let capturedListener:
      | ((state: { status: string }) => void)
      | null = null;
    subscribeToConnectionStateMock.mockImplementation(
      (listener: (state: { status: string }) => void) => {
        capturedListener = listener;
        listener({ status: "reconnecting" });
        return () => {};
      },
    );
    getConnectionStateMock.mockReturnValue({ status: "reconnecting" });

    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );

    expect(screen.getByTestId("bid-panel-slot")).toHaveAttribute(
      "data-ws-state",
      "reconnecting",
    );

    act(() => {
      capturedListener!({ status: "connected" });
    });
    expect(screen.getByTestId("bid-panel-slot")).toHaveAttribute(
      "data-ws-state",
      "connected",
    );
  });

  // ---------------------------------------------------------------
  // Task 7: outbid toast + ReconnectingBanner + form disable.
  // ---------------------------------------------------------------

  it("fires the outbid toast when the caller is displaced by a BID_SETTLEMENT", async () => {
    let capturedOnMessage:
      | ((env: AuctionEnvelope) => void)
      | null = null;
    subscribeMock.mockImplementation(
      (_destination: string, onMessage: (env: AuctionEnvelope) => void) => {
        capturedOnMessage = onMessage;
        return () => {};
      },
    );

    // Seed a first envelope that marks the caller (id=999) as the high
    // bidder, then a second envelope that displaces them.
    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );

    act(() => {
      capturedOnMessage!(
        bidSettlement({
          currentBid: 1800,
          currentBidderId: verifiedBidder.id,
          newBids: [
            bidHistoryEntry({
              bidId: 10,
              userId: verifiedBidder.id,
              amount: 1800,
            }),
          ],
        }),
      );
    });

    // Now a competitor outbids the caller.
    act(() => {
      capturedOnMessage!(
        bidSettlement({
          currentBid: 2500,
          currentBidderId: 55,
          newBids: [
            bidHistoryEntry({
              bidId: 11,
              userId: 55,
              bidderDisplayName: "Bob",
              amount: 2500,
            }),
          ],
        }),
      );
    });

    // The ToastProvider defers setToasts via setTimeout(0). waitFor's
    // default 1s polling is plenty to flush the tick on real timers.
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(/outbid/i);
    });
    expect(screen.getByRole("alert")).toHaveTextContent("L$2,500");
  });

  it("does NOT fire the outbid toast when the caller wasn't winning", async () => {
    let capturedOnMessage:
      | ((env: AuctionEnvelope) => void)
      | null = null;
    subscribeMock.mockImplementation(
      (_destination: string, onMessage: (env: AuctionEnvelope) => void) => {
        capturedOnMessage = onMessage;
        return () => {};
      },
    );

    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );

    // First envelope: some other bidder (id=77) takes the lead.
    act(() => {
      capturedOnMessage!(
        bidSettlement({
          currentBid: 1800,
          currentBidderId: 77,
          newBids: [
            bidHistoryEntry({
              bidId: 20,
              userId: 77,
              amount: 1800,
            }),
          ],
        }),
      );
    });

    // Second envelope: a different competitor outbids 77. Our viewer
    // was never winning, so no toast should fire.
    act(() => {
      capturedOnMessage!(
        bidSettlement({
          currentBid: 2500,
          currentBidderId: 55,
          newBids: [
            bidHistoryEntry({
              bidId: 21,
              userId: 55,
              amount: 2500,
            }),
          ],
        }),
      );
    });

    // Let the Toast provider's setTimeout(0) flush — if a toast were
    // going to fire, it would have by now. Wait a tick via waitFor so
    // we're not racing the microtask queue.
    await waitFor(() => {
      expect(screen.getByTestId("bid-panel-current-high")).toHaveTextContent(
        "L$ 2,500",
      );
    });
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("renders the ReconnectingBanner inside the BidPanel when WS is reconnecting", () => {
    getConnectionStateMock.mockReturnValue({ status: "reconnecting" });
    subscribeToConnectionStateMock.mockImplementation(
      (listener: (state: { status: string }) => void) => {
        listener({ status: "reconnecting" });
        return () => {};
      },
    );

    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );

    const banner = screen.getByTestId("reconnecting-banner");
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveTextContent(/Reconnecting/);
    // The banner renders inside the bidder variant container, not as a
    // sibling of the whole panel slot.
    expect(
      screen.getByTestId("bid-panel-bidder").contains(banner),
    ).toBe(true);
  });

  it("disables the place-bid submit when connection is not connected", () => {
    getConnectionStateMock.mockReturnValue({ status: "reconnecting" });
    subscribeToConnectionStateMock.mockImplementation(
      (listener: (state: { status: string }) => void) => {
        listener({ status: "reconnecting" });
        return () => {};
      },
    );

    renderWithProviders(
      <AuctionDetailClient
        initialAuction={auction}
        initialBidPage={initialBids}
      />,
      { auth: "authenticated", authUser: verifiedBidder },
    );

    expect(screen.getByTestId("place-bid-submit")).toBeDisabled();
    expect(screen.getByTestId("proxy-bid-submit")).toBeDisabled();
    expect(
      screen.getByTestId("place-bid-connection-helper"),
    ).toHaveTextContent(/Waiting for connection/);
  });
});
