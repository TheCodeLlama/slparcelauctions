import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { ResultsGrid } from "./ResultsGrid";
import { defaultAuctionSearchQuery } from "@/lib/search/url-codec";
import type { AuctionSearchResultDto } from "@/types/search";

const sampleListing: AuctionSearchResultDto = {
  publicId: "00000000-0000-0000-0000-000000000001",
  title: "Sample Lot",
  status: "ACTIVE",
  endOutcome: null,
  parcel: {
    auctionPublicId: "00000000-0000-0000-0000-000000000001",
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
    publicId: "00000000-0000-0000-0000-000000000007",
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

  it("treats sort-only change as no-filters empty state", () => {
    // Regression: a user landing on /browse?sort=ending_soonest with no
    // actual filter fields applied should see "no active auctions yet",
    // not "no auctions match your filters".
    renderWithProviders(
      <ResultsGrid
        listings={[]}
        isLoading={false}
        isError={false}
        query={{ ...defaultAuctionSearchQuery, sort: "ending_soonest" }}
        onClearFilters={() => {}}
      />,
    );
    expect(screen.getByText(/no active auctions yet/i)).toBeInTheDocument();
    expect(
      screen.queryByText(/no auctions match your filters/i),
    ).toBeNull();
  });

  it("treats page-only change as no-filters empty state", () => {
    renderWithProviders(
      <ResultsGrid
        listings={[]}
        isLoading={false}
        isError={false}
        query={{ ...defaultAuctionSearchQuery, page: 2 }}
        onClearFilters={() => {}}
      />,
    );
    expect(screen.getByText(/no active auctions yet/i)).toBeInTheDocument();
  });

  it("treats seller-only query as no-filters when sellerId is a fixedFilter", () => {
    // On /users/{id}/listings the seller id is a pinned fixedFilter, not
    // a user-applied filter. An empty result should still render the
    // "no active auctions yet" copy — there's nothing the visitor can
    // clear here.
    renderWithProviders(
      <ResultsGrid
        listings={[]}
        isLoading={false}
        isError={false}
        query={{ ...defaultAuctionSearchQuery, sellerPublicId: "00000000-0000-0000-0000-00000000002a" }}
        fixedFilters={{ sellerPublicId: "00000000-0000-0000-0000-00000000002a" }}
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

  it("renders in dark mode", () => {
    renderWithProviders(
      <ResultsGrid
        listings={[sampleListing]}
        isLoading={false}
        isError={false}
        query={defaultAuctionSearchQuery}
        onClearFilters={() => {}}
      />,
      { theme: "dark", forceTheme: true },
    );
    expect(screen.getByText("Sample Lot")).toBeInTheDocument();
  });
});
