import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import type {
  AuctionEndOutcome,
  PublicAuctionResponse,
} from "@/types/auction";
import type { ConnectionState } from "@/lib/ws/types";
import { StickyBidBar } from "./StickyBidBar";

type EndedExtensions = {
  endOutcome?: AuctionEndOutcome;
  finalBidAmount?: number | null;
  winnerUserId?: number | null;
  winnerDisplayName?: string | null;
};

/**
 * Fixture factory — public auction DTO with sensible defaults. Tests
 * override only the fields that matter for the scenario under test.
 */
function auctionFixture(
  overrides: Partial<PublicAuctionResponse> & EndedExtensions = {},
): PublicAuctionResponse & EndedExtensions {
  return {
    id: 42,
    sellerId: 100,
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
      description: "Beachfront",
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
    endsAt: new Date(Date.now() + 2 * 3_600_000).toISOString(),
    originalEndsAt: new Date(Date.now() + 2 * 3_600_000).toISOString(),
    sellerDesc: null,
    tags: [],
    photos: [],
    ...overrides,
  };
}

const connected: ConnectionState = { status: "connected" };
const reconnecting: ConnectionState = { status: "reconnecting" };

describe("StickyBidBar", () => {
  it("renders the unauth variant with a Sign in CTA pointing at the auction", () => {
    renderWithProviders(
      <StickyBidBar
        auction={auctionFixture()}
        currentUser={null}
        connectionState={connected}
        onOpenSheet={vi.fn()}
      />,
    );
    const bar = screen.getByTestId("sticky-bid-bar");
    expect(bar).toHaveAttribute("data-variant", "unauth");
    expect(screen.getByTestId("sticky-bid-bar-high")).toHaveTextContent(
      "L$ 1,500",
    );
    const cta = screen.getByTestId("sticky-bid-bar-cta");
    expect(cta).toHaveTextContent(/Sign in to bid/i);
    expect(cta).toHaveAttribute(
      "href",
      `/login?next=${encodeURIComponent("/auction/42")}`,
    );
  });

  it("renders the unverified variant with a link to /dashboard/overview", () => {
    renderWithProviders(
      <StickyBidBar
        auction={auctionFixture()}
        currentUser={{ id: 555, verified: false }}
        connectionState={connected}
        onOpenSheet={vi.fn()}
      />,
    );
    const bar = screen.getByTestId("sticky-bid-bar");
    expect(bar).toHaveAttribute("data-variant", "unverified");
    const cta = screen.getByTestId("sticky-bid-bar-cta");
    expect(cta).toHaveTextContent(/Verify to bid/i);
    expect(cta).toHaveAttribute("href", "/dashboard/overview");
  });

  it("renders the seller variant with 'Your auction' copy and no CTA", () => {
    renderWithProviders(
      <StickyBidBar
        auction={auctionFixture({ sellerId: 777 })}
        currentUser={{ id: 777, verified: true }}
        connectionState={connected}
        onOpenSheet={vi.fn()}
      />,
    );
    const bar = screen.getByTestId("sticky-bid-bar");
    expect(bar).toHaveAttribute("data-variant", "seller");
    expect(bar).toHaveTextContent(/Your auction/i);
    expect(screen.queryByTestId("sticky-bid-bar-cta")).toBeNull();
  });

  it("renders the bidder variant with a Bid now button that opens the sheet", async () => {
    const onOpenSheet = vi.fn();
    renderWithProviders(
      <StickyBidBar
        auction={auctionFixture()}
        currentUser={{ id: 555, verified: true }}
        connectionState={connected}
        onOpenSheet={onOpenSheet}
      />,
    );
    const bar = screen.getByTestId("sticky-bid-bar");
    expect(bar).toHaveAttribute("data-variant", "bidder");
    const cta = screen.getByTestId("sticky-bid-bar-cta");
    expect(cta).toHaveTextContent(/Bid now/i);
    await userEvent.click(cta);
    expect(onOpenSheet).toHaveBeenCalledTimes(1);
  });

  it("renders the ended variant with winner copy and no CTA", () => {
    renderWithProviders(
      <StickyBidBar
        auction={auctionFixture({
          status: "ENDED",
          currentHighBid: 2500,
          currentBid: 2500,
          bidCount: 3,
          endOutcome: "SOLD",
          finalBidAmount: 2500,
          winnerUserId: 42,
          winnerDisplayName: "Alice",
        })}
        currentUser={{ id: 555, verified: true }}
        connectionState={connected}
        onOpenSheet={vi.fn()}
      />,
    );
    const bar = screen.getByTestId("sticky-bid-bar");
    expect(bar).toHaveAttribute("data-variant", "ended");
    const ended = screen.getByTestId("sticky-bid-bar-ended");
    expect(ended).toHaveTextContent(/Sold/i);
    expect(ended).toHaveTextContent("L$2,500");
    expect(ended).toHaveTextContent(/@Alice/);
    expect(screen.queryByTestId("sticky-bid-bar-cta")).toBeNull();
  });

  it("renders the reserve-not-met ended variant without winner copy", () => {
    renderWithProviders(
      <StickyBidBar
        auction={auctionFixture({
          status: "ENDED",
          currentHighBid: 600,
          currentBid: 600,
          bidCount: 2,
          hasReserve: true,
          reserveMet: false,
          endOutcome: "RESERVE_NOT_MET",
        })}
        currentUser={null}
        connectionState={connected}
        onOpenSheet={vi.fn()}
      />,
    );
    const bar = screen.getByTestId("sticky-bid-bar");
    expect(bar).toHaveAttribute("data-variant", "ended");
    expect(screen.getByTestId("sticky-bid-bar-ended")).toHaveTextContent(
      /Reserve not met/i,
    );
    expect(screen.queryByTestId("sticky-bid-bar-cta")).toBeNull();
  });

  it("disables the Bid now button and swaps to Reconnecting when WS is not connected", async () => {
    const onOpenSheet = vi.fn();
    renderWithProviders(
      <StickyBidBar
        auction={auctionFixture()}
        currentUser={{ id: 555, verified: true }}
        connectionState={reconnecting}
        onOpenSheet={onOpenSheet}
      />,
    );
    const cta = screen.getByTestId("sticky-bid-bar-cta");
    expect(cta).toBeDisabled();
    expect(cta).toHaveTextContent(/Reconnecting/i);
    expect(cta).toHaveAttribute("data-disabled-reason", "disconnected");
    // Clicking a disabled button is a no-op, so the handler must not fire.
    await userEvent.click(cta);
    expect(onOpenSheet).not.toHaveBeenCalled();
  });

  it("exposes the connection state on the bar root for CSS / analytics hooks", () => {
    renderWithProviders(
      <StickyBidBar
        auction={auctionFixture()}
        currentUser={{ id: 555, verified: true }}
        connectionState={{ status: "error", detail: "boom" }}
        onOpenSheet={vi.fn()}
      />,
    );
    expect(screen.getByTestId("sticky-bid-bar")).toHaveAttribute(
      "data-ws-state",
      "error",
    );
  });
});
