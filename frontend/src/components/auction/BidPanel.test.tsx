import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { BidPanel, deriveBidPanelVariant } from "./BidPanel";
import type {
  ProxyBidResponse,
  PublicAuctionResponse,
} from "@/types/auction";

// Hoisted ws module mock — the BidPanel bidder variant pulls in
// PlaceBidForm + ProxyBidSection, which in turn import the ws hooks
// indirectly (useConnectionState is passed in as a prop here, but the
// ws client module still loads for the connection state constants).
vi.mock("@/lib/ws/client", () => ({
  subscribe: vi.fn(),
  subscribeToConnectionState: vi.fn(),
  getConnectionState: vi.fn(() => ({ status: "connected" })),
}));

function publicAuction(
  overrides: Partial<PublicAuctionResponse> = {},
): PublicAuctionResponse {
  return {
    publicId: "00000000-0000-0000-0000-000000000007",
    sellerPublicId: "00000000-0000-0000-0000-000000000064",
    title: "Featured Parcel Listing",
    parcel: {
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
      description: "Beachfront parcel",
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: true,
      verifiedAt: null,
      lastChecked: null,
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

const connected = { status: "connected" as const };

describe("deriveBidPanelVariant", () => {
  it("returns 'ended' when status is ENDED regardless of viewer", () => {
    const auction = publicAuction({ status: "ENDED" });
    expect(deriveBidPanelVariant(auction, null)).toBe("ended");
    expect(
      deriveBidPanelVariant(auction, { publicId: "00000000-0000-0000-0000-000000000001", verified: true }),
    ).toBe("ended");
    // Even a seller-viewer sees the ended panel.
    expect(
      deriveBidPanelVariant(auction, { publicId: "00000000-0000-0000-0000-000000000064", verified: true }),
    ).toBe("ended");
  });

  it("returns 'unauth' when there is no current user", () => {
    expect(deriveBidPanelVariant(publicAuction(), null)).toBe("unauth");
  });

  it("returns 'unverified' when user is not SL-verified", () => {
    expect(
      deriveBidPanelVariant(publicAuction(), { publicId: "00000000-0000-0000-0000-000000000001", verified: false }),
    ).toBe("unverified");
  });

  it("returns 'seller' when the viewer owns the auction", () => {
    expect(
      deriveBidPanelVariant(publicAuction(), { publicId: "00000000-0000-0000-0000-000000000064", verified: true }),
    ).toBe("seller");
  });

  it("returns 'bidder' for any other verified viewer", () => {
    expect(
      deriveBidPanelVariant(publicAuction(), { publicId: "00000000-0000-0000-0000-000000000001", verified: true }),
    ).toBe("bidder");
  });
});

describe("BidPanel", () => {
  beforeEach(() => {
    if (typeof window !== "undefined") {
      window.sessionStorage.clear();
    }
  });

  it("renders the unauth gate when no current user", () => {
    renderWithProviders(
      <BidPanel
        auction={publicAuction()}
        currentUser={null}
        existingProxy={null}
        connectionState={connected}
      />,
    );
    expect(screen.getByTestId("auth-gate-message")).toHaveAttribute(
      "data-kind",
      "unauth",
    );
  });

  it("renders the unverified gate when user.verified is false", () => {
    renderWithProviders(
      <BidPanel
        auction={publicAuction()}
        currentUser={{ publicId: "00000000-0000-0000-0000-000000000001", verified: false }}
        existingProxy={null}
        connectionState={connected}
      />,
    );
    expect(screen.getByTestId("auth-gate-message")).toHaveAttribute(
      "data-kind",
      "unverified",
    );
  });

  it("renders the seller gate when the viewer is the seller", () => {
    renderWithProviders(
      <BidPanel
        auction={publicAuction({ sellerPublicId: "00000000-0000-0000-0000-00000000002a" })}
        currentUser={{ publicId: "00000000-0000-0000-0000-00000000002a", verified: true }}
        existingProxy={null}
        connectionState={connected}
      />,
    );
    expect(screen.getByTestId("auth-gate-message")).toHaveAttribute(
      "data-kind",
      "seller",
    );
  });

  it("renders the AuctionEndedPanel for ENDED auctions", () => {
    // Backend invariant: endOutcome is always projected on ENDED
    // auctions post Epic 05 sub-spec 1. AuctionEndedPanel throws if
    // the field is null, so the fixture supplies it explicitly.
    const ended = {
      ...publicAuction({ status: "ENDED" }),
      endOutcome: "SOLD" as const,
      finalBidAmount: 1500,
      winnerPublicId: "00000000-0000-0000-0000-00000000002a",
      winnerDisplayName: "Winner",
    };
    renderWithProviders(
      <BidPanel
        auction={ended}
        currentUser={{ publicId: "00000000-0000-0000-0000-000000000001", verified: true }}
        existingProxy={null}
        connectionState={connected}
      />,
    );
    expect(screen.getByTestId("auction-ended-panel")).toBeInTheDocument();
    // Default stub is gone — Task 6 replaces it with the real panel.
    expect(
      screen.queryByTestId("bid-panel-ended-stub"),
    ).not.toBeInTheDocument();
  });

  it("renders the bidder panel with forms for a verified non-seller", () => {
    renderWithProviders(
      <BidPanel
        auction={publicAuction()}
        currentUser={{ publicId: "00000000-0000-0000-0000-000000000001", verified: true }}
        existingProxy={null}
        connectionState={connected}
      />,
    );
    expect(screen.getByTestId("bid-panel-bidder")).toBeInTheDocument();
    expect(screen.getByTestId("place-bid-form")).toBeInTheDocument();
    expect(screen.getByTestId("proxy-bid-section")).toHaveAttribute(
      "data-mode",
      "create",
    );
  });

  it("shows the ACTIVE proxy callout when the user has a live proxy", () => {
    const proxy: ProxyBidResponse = {
      proxyBidPublicId: "00000000-0000-0000-0000-000000000001",
      auctionPublicId: "00000000-0000-0000-0000-000000000007",
      maxAmount: 5000,
      status: "ACTIVE",
      createdAt: "",
      updatedAt: "",
    };
    renderWithProviders(
      <BidPanel
        auction={publicAuction()}
        currentUser={{ publicId: "00000000-0000-0000-0000-000000000001", verified: true }}
        existingProxy={proxy}
        connectionState={connected}
      />,
    );
    expect(screen.getByTestId("proxy-bid-section")).toHaveAttribute(
      "data-mode",
      "active",
    );
    expect(screen.getByTestId("proxy-active-callout")).toHaveTextContent(
      "L$5,000",
    );
  });

  it("renders the buy-now callout when auction has a buy-now price", () => {
    renderWithProviders(
      <BidPanel
        auction={publicAuction({ buyNowPrice: 25_000 })}
        currentUser={{ publicId: "00000000-0000-0000-0000-000000000001", verified: true }}
        existingProxy={null}
        connectionState={connected}
      />,
    );
    expect(screen.getByTestId("bid-panel-buy-now-callout")).toHaveTextContent(
      "L$25,000",
    );
  });
});
