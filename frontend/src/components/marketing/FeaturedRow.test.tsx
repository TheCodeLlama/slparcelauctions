import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { FeaturedRow } from "./FeaturedRow";
import type { AuctionSearchResultDto } from "@/types/search";

function sampleListing(
  overrides: Partial<AuctionSearchResultDto> = {},
): AuctionSearchResultDto {
  return {
    id: 1,
    title: "Premium Waterfront",
    status: "ACTIVE",
    endOutcome: null,
    parcel: {
      id: 11,
      name: "Bayside Lot",
      region: "Tula",
      area: 1024,
      maturity: "MODERATE",
      snapshotUrl: "/snap.jpg",
      gridX: 1,
      gridY: 2,
      positionX: 80,
      positionY: 104,
      positionZ: 89,
      tags: ["BEACHFRONT"],
    },
    primaryPhotoUrl: "/photo.jpg",
    seller: {
      id: 7,
      displayName: "seller",
      avatarUrl: null,
      averageRating: 4.8,
      reviewCount: 12,
    },
    verificationTier: "BOT",
    currentBid: 12500,
    startingBid: 5000,
    reservePrice: 10000,
    reserveMet: true,
    buyNowPrice: null,
    bidCount: 7,
    endsAt: new Date(Date.now() + 5 * 3_600_000).toISOString(),
    snipeProtect: true,
    snipeWindowMin: 5,
    distanceRegions: null,
    ...overrides,
  };
}

describe("FeaturedRow", () => {
  it("renders the header, View all link, and card rail when fulfilled with content", () => {
    const result = {
      status: "fulfilled" as const,
      value: {
        content: [
          sampleListing({ id: 1, title: "Alpha Parcel" }),
          sampleListing({ id: 2, title: "Beta Parcel" }),
        ],
      },
    };

    renderWithProviders(
      <FeaturedRow
        title="Ending Soon"
        sortLink="/browse?sort=ending_soonest"
        result={result}
      />,
    );

    // Header + action
    expect(
      screen.getByRole("heading", { name: "Ending Soon" }),
    ).toBeInTheDocument();
    const viewAll = screen.getByRole("link", { name: /view all/i });
    expect(viewAll).toHaveAttribute("href", "/browse?sort=ending_soonest");

    // Grid exists, empty/unavailable placeholders do not
    expect(screen.getByTestId("featured-row-grid")).toBeInTheDocument();
    expect(screen.queryByTestId("featured-row-empty")).toBeNull();
    expect(screen.queryByTestId("featured-row-unavailable")).toBeNull();

    // Both cards rendered
    expect(screen.getByText("Alpha Parcel")).toBeInTheDocument();
    expect(screen.getByText("Beta Parcel")).toBeInTheDocument();
  });

  it("renders the empty placeholder when fulfilled with zero rows", () => {
    const result = {
      status: "fulfilled" as const,
      value: { content: [] },
    };

    renderWithProviders(
      <FeaturedRow
        title="Ending Soon"
        sortLink="/browse?sort=ending_soonest"
        result={result}
      />,
    );

    expect(screen.getByTestId("featured-row-empty")).toHaveTextContent(
      /no listings ending soon right now/i,
    );
    expect(screen.queryByTestId("featured-row-grid")).toBeNull();
    expect(screen.queryByTestId("featured-row-unavailable")).toBeNull();
  });

  it("uses emptyMessage override when provided on the fulfilled-empty branch", () => {
    const result = {
      status: "fulfilled" as const,
      value: { content: [] },
    };

    renderWithProviders(
      <FeaturedRow
        title="Most Active"
        sortLink="/browse?sort=most_bids"
        result={result}
        emptyMessage="The grid is quiet — check back soon."
      />,
    );

    expect(screen.getByTestId("featured-row-empty")).toHaveTextContent(
      "The grid is quiet — check back soon.",
    );
  });

  it("renders the temporarily-unavailable placeholder when the result is rejected", () => {
    const result: PromiseSettledResult<{ content: AuctionSearchResultDto[] }> = {
      status: "rejected",
      reason: new Error("boom"),
    };

    renderWithProviders(
      <FeaturedRow
        title="Ending Soon"
        sortLink="/browse?sort=ending_soonest"
        result={result}
      />,
    );

    expect(screen.getByTestId("featured-row-unavailable")).toHaveTextContent(
      /ending soon auctions are temporarily unavailable/i,
    );
    expect(screen.queryByTestId("featured-row-grid")).toBeNull();
    expect(screen.queryByTestId("featured-row-empty")).toBeNull();
    // Header + View all link should still render so the page structure stays
    // stable when a single rail goes down.
    expect(
      screen.getByRole("heading", { name: "Ending Soon" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /view all/i }),
    ).toHaveAttribute("href", "/browse?sort=ending_soonest");
  });

  it("dark mode renders without visual regressions", () => {
    const result = {
      status: "fulfilled" as const,
      value: { content: [sampleListing()] },
    };

    renderWithProviders(
      <FeaturedRow
        title="Ending Soon"
        sortLink="/browse?sort=ending_soonest"
        result={result}
      />,
      { theme: "dark", forceTheme: true },
    );

    expect(
      screen.getByRole("heading", { name: "Ending Soon" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Premium Waterfront")).toBeInTheDocument();
  });
});
