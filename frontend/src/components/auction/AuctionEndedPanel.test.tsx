import { describe, it, expect, beforeEach, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type {
  AuctionEndOutcome,
  PublicAuctionResponse,
} from "@/types/auction";
import { AuctionEndedPanel } from "./AuctionEndedPanel";

vi.mock("@/lib/ws/client", () => ({
  subscribe: vi.fn(),
  subscribeToConnectionState: vi.fn(),
  getConnectionState: vi.fn(() => ({ status: "connected" })),
}));

type EndedExtensions = {
  endOutcome?: AuctionEndOutcome;
  finalBidAmount?: number | null;
  winnerUserId?: number | null;
  winnerDisplayName?: string | null;
};

function endedAuction(
  overrides: Partial<PublicAuctionResponse> & EndedExtensions = {},
): PublicAuctionResponse & EndedExtensions {
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
      description: "Beachfront",
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: true,
      verifiedAt: "2026-04-20T00:00:00Z",
      lastChecked: "2026-04-20T00:00:00Z",
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "ENDED",
    verificationTier: "SCRIPT",
    startingBid: 500,
    hasReserve: false,
    reserveMet: true,
    buyNowPrice: null,
    currentBid: 2500,
    bidCount: 3,
    currentHighBid: 2500,
    bidderCount: 2,
    durationHours: 72,
    snipeProtect: true,
    snipeWindowMin: 10,
    startsAt: "2026-04-19T00:00:00Z",
    endsAt: "2026-04-20T00:00:00Z",
    originalEndsAt: "2026-04-20T00:00:00Z",
    sellerDesc: null,
    tags: [],
    photos: [],
    ...overrides,
  };
}

describe("AuctionEndedPanel", () => {
  beforeEach(() => {
    // Default profile handler — only invoked when winnerDisplayName is
    // missing. Variants that supply the name shouldn't hit this.
    server.use(
      http.get("*/api/v1/users/:id", ({ params }) =>
        HttpResponse.json({
          id: Number(params.id),
          displayName: "Winner Name",
          bio: null,
          profilePicUrl: null,
          slAvatarUuid: null,
          slAvatarName: "winner.resident",
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

  it("SOLD variant — renders final price and winner link", async () => {
    const auction = endedAuction({
      endOutcome: "SOLD",
      finalBidAmount: 2500,
      winnerUserId: 42,
      winnerDisplayName: "Alice",
    });
    renderWithProviders(
      <AuctionEndedPanel auction={auction} currentUser={null} />,
    );
    const panel = screen.getByTestId("auction-ended-panel");
    expect(panel).toHaveAttribute("data-outcome", "SOLD");
    expect(screen.getByTestId("auction-ended-headline")).toHaveTextContent(
      "Sold for L$2,500",
    );
    const winner = screen.getByTestId("auction-ended-winner");
    expect(winner).toHaveAttribute("href", "/users/42");
    expect(winner).toHaveTextContent("Alice");
  });

  it("BOUGHT_NOW variant — renders buy-now headline", () => {
    const auction = endedAuction({
      endOutcome: "BOUGHT_NOW",
      finalBidAmount: 50_000,
      winnerUserId: 42,
      winnerDisplayName: "Alice",
    });
    renderWithProviders(
      <AuctionEndedPanel auction={auction} currentUser={null} />,
    );
    expect(screen.getByTestId("auction-ended-headline")).toHaveTextContent(
      "Sold at buy-now price L$50,000",
    );
  });

  it("RESERVE_NOT_MET variant — no winner row, shows highest bid", () => {
    const auction = endedAuction({
      endOutcome: "RESERVE_NOT_MET",
      finalBidAmount: null,
      winnerUserId: null,
      winnerDisplayName: null,
      currentHighBid: 1800,
    });
    renderWithProviders(
      <AuctionEndedPanel auction={auction} currentUser={null} />,
    );
    expect(screen.getByTestId("auction-ended-headline")).toHaveTextContent(
      "Reserve not met",
    );
    expect(screen.getByText(/Highest bid was L\$1,800/)).toBeInTheDocument();
    expect(
      screen.queryByTestId("auction-ended-winner"),
    ).not.toBeInTheDocument();
  });

  it("NO_BIDS variant — shows starting bid", () => {
    const auction = endedAuction({
      endOutcome: "NO_BIDS",
      finalBidAmount: null,
      winnerUserId: null,
      startingBid: 750,
      bidCount: 0,
      currentHighBid: null,
    });
    renderWithProviders(
      <AuctionEndedPanel auction={auction} currentUser={null} />,
    );
    expect(screen.getByTestId("auction-ended-headline")).toHaveTextContent(
      "Ended with no bids",
    );
    expect(screen.getByText(/Starting bid was L\$750/)).toBeInTheDocument();
  });

  it("shows winner overlay when current user is the winner", () => {
    const auction = endedAuction({
      endOutcome: "SOLD",
      finalBidAmount: 2500,
      winnerUserId: 42,
      winnerDisplayName: "Alice",
    });
    renderWithProviders(
      <AuctionEndedPanel auction={auction} currentUser={{ id: 42 }} />,
    );
    expect(
      screen.getByTestId("auction-ended-winner-overlay"),
    ).toHaveTextContent("You won this auction");
  });

  it("shows seller overlay when current user is the seller", () => {
    const auction = endedAuction({
      endOutcome: "SOLD",
      finalBidAmount: 2500,
      winnerUserId: 42,
      winnerDisplayName: "Alice",
    });
    renderWithProviders(
      <AuctionEndedPanel auction={auction} currentUser={{ id: 100 }} />,
    );
    expect(
      screen.getByTestId("auction-ended-seller-overlay"),
    ).toHaveTextContent("Escrow flow opens in Epic 05");
  });

  it("renders neutral when viewer is neither winner nor seller", () => {
    const auction = endedAuction({
      endOutcome: "SOLD",
      finalBidAmount: 2500,
      winnerUserId: 42,
      winnerDisplayName: "Alice",
    });
    renderWithProviders(
      <AuctionEndedPanel auction={auction} currentUser={{ id: 99 }} />,
    );
    expect(
      screen.queryByTestId("auction-ended-winner-overlay"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("auction-ended-seller-overlay"),
    ).not.toBeInTheDocument();
  });

  it("fetches winner display name when missing from DTO", async () => {
    const auction = endedAuction({
      endOutcome: "SOLD",
      finalBidAmount: 2500,
      winnerUserId: 42,
      winnerDisplayName: null,
    });
    renderWithProviders(
      <AuctionEndedPanel auction={auction} currentUser={null} />,
    );
    // Initially falls back to "User 42", then resolves to "Winner Name".
    await waitFor(() =>
      expect(
        screen.getByTestId("auction-ended-winner"),
      ).toHaveTextContent("Winner Name"),
    );
  });
});
