import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import type {
  AuctionEndOutcome,
  PublicAuctionResponse,
} from "@/types/auction";
import { AuctionEndedRow } from "./AuctionEndedRow";

type EndedExtensions = {
  endOutcome?: AuctionEndOutcome;
  finalBidAmount?: number | null;
};

function endedAuction(
  overrides: Partial<PublicAuctionResponse> & EndedExtensions = {},
): PublicAuctionResponse & EndedExtensions {
  return {
    id: 7,
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
      continentName: null,
      areaSqm: 1024,
      description: null,
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

describe("AuctionEndedRow", () => {
  it("renders 'Auction ended — L$N' for SOLD", () => {
    renderWithProviders(
      <AuctionEndedRow
        auction={endedAuction({ endOutcome: "SOLD", finalBidAmount: 2500 })}
      />,
    );
    const row = screen.getByTestId("auction-ended-row");
    expect(row).toHaveAttribute("data-outcome", "SOLD");
    expect(row).toHaveTextContent("Auction ended — L$2,500");
  });

  it("renders 'Auction ended — L$N' for BOUGHT_NOW", () => {
    renderWithProviders(
      <AuctionEndedRow
        auction={endedAuction({
          endOutcome: "BOUGHT_NOW",
          finalBidAmount: 50_000,
        })}
      />,
    );
    expect(screen.getByTestId("auction-ended-row")).toHaveTextContent(
      "Auction ended — L$50,000",
    );
  });

  it("renders 'Ended — no winner' for RESERVE_NOT_MET", () => {
    renderWithProviders(
      <AuctionEndedRow
        auction={endedAuction({
          endOutcome: "RESERVE_NOT_MET",
          finalBidAmount: null,
        })}
      />,
    );
    expect(screen.getByTestId("auction-ended-row")).toHaveTextContent(
      "Ended — no winner",
    );
  });

  it("renders 'Ended — no winner' for NO_BIDS", () => {
    renderWithProviders(
      <AuctionEndedRow
        auction={endedAuction({
          endOutcome: "NO_BIDS",
          finalBidAmount: null,
        })}
      />,
    );
    expect(screen.getByTestId("auction-ended-row")).toHaveTextContent(
      "Ended — no winner",
    );
  });
});
