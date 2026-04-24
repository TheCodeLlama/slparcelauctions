import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { ResultsGrid } from "./ResultsGrid";
import { defaultAuctionSearchQuery } from "@/lib/search/url-codec";
import type { AuctionSearchResultDto } from "@/types/search";

const sampleListing: AuctionSearchResultDto = {
  id: 1,
  title: "Sample Lot",
  status: "ACTIVE",
  endOutcome: null,
  parcel: {
    id: 10,
    name: "Lot",
    region: "Tula",
    area: 512,
    maturity: "GENERAL",
    snapshotUrl: null,
    gridX: 0,
    gridY: 0,
    positionX: 0,
    positionY: 0,
    positionZ: 0,
    tags: [],
  },
  primaryPhotoUrl: null,
  seller: {
    id: 7,
    displayName: "seller",
    avatarUrl: null,
    averageRating: null,
    reviewCount: null,
  },
  verificationTier: "SCRIPT",
  currentBid: 100,
  startingBid: 100,
  reservePrice: null,
  reserveMet: false,
  buyNowPrice: null,
  bidCount: 0,
  endsAt: new Date(Date.now() + 3600_000).toISOString(),
  snipeProtect: false,
  snipeWindowMin: null,
  distanceRegions: null,
};

describe("ResultsGrid", () => {
  it("renders skeleton placeholders when loading", () => {
    renderWithProviders(
      <ResultsGrid
        listings={[]}
        isLoading={true}
        isError={false}
        query={defaultAuctionSearchQuery}
        onClearFilters={() => {}}
      />,
    );
    expect(screen.getByLabelText(/loading listings/i)).toBeInTheDocument();
  });

  it("renders a listing grid when populated", () => {
    renderWithProviders(
      <ResultsGrid
        listings={[sampleListing]}
        isLoading={false}
        isError={false}
        query={defaultAuctionSearchQuery}
        onClearFilters={() => {}}
      />,
    );
    expect(screen.getByText("Sample Lot")).toBeInTheDocument();
  });

  it("shows 'no-filters' empty state when baseline query yields zero", () => {
    renderWithProviders(
      <ResultsGrid
        listings={[]}
        isLoading={false}
        isError={false}
        query={defaultAuctionSearchQuery}
        onClearFilters={() => {}}
      />,
    );
    expect(screen.getByText(/no active auctions yet/i)).toBeInTheDocument();
  });

  it("shows 'no-match' empty state with CTA when filters are applied", async () => {
    const onClear = vi.fn();
    renderWithProviders(
      <ResultsGrid
        listings={[]}
        isLoading={false}
        isError={false}
        query={{ ...defaultAuctionSearchQuery, region: "Tula" }}
        onClearFilters={onClear}
      />,
    );
    expect(
      screen.getByText(/no auctions match your filters/i),
    ).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: /clear all filters/i }),
    );
    expect(onClear).toHaveBeenCalled();
  });

  it("shows TOO_MANY_REQUESTS panel when errorCode is set", async () => {
    const onRetry = vi.fn();
    renderWithProviders(
      <ResultsGrid
        listings={[]}
        isLoading={false}
        isError={true}
        errorCode="TOO_MANY_REQUESTS"
        query={defaultAuctionSearchQuery}
        onClearFilters={() => {}}
        onRetry={onRetry}
      />,
    );
    expect(screen.getByText(/too many searches/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /try again/i }));
    expect(onRetry).toHaveBeenCalled();
  });

  it("shows generic error panel for other errors", () => {
    renderWithProviders(
      <ResultsGrid
        listings={[]}
        isLoading={false}
        isError={true}
        errorCode="INTERNAL_ERROR"
        query={defaultAuctionSearchQuery}
        onClearFilters={() => {}}
      />,
    );
    expect(screen.getByText(/couldn't load listings/i)).toBeInTheDocument();
  });
});
