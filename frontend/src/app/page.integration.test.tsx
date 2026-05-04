import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/msw/server";
import type { AuctionSearchResultDto } from "@/types/search";
import HomePage from "./page";

// ---------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------

function sampleListing(
  overrides: Partial<AuctionSearchResultDto> = {},
): AuctionSearchResultDto {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    title: "Sample Parcel",
    status: "ACTIVE",
    endOutcome: null,
    parcel: {
      auctionPublicId: "00000000-0000-0000-0000-000000000001",
      name: "Sample Lot",
      region: "Tula",
      area: 1024,
      maturity: "MODERATE",
      snapshotUrl: null,
      gridX: 1,
      gridY: 2,
      positionX: 80,
      positionY: 104,
      positionZ: 89,
      tags: ["BEACHFRONT"],
    },
    primaryPhotoUrl: null,
    seller: {
      publicId: "00000000-0000-0000-0000-000000000007",
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

// ---------------------------------------------------------------
// Tests — server component invocation pattern mirrors
// app/auction/[id]/page.integration.test.tsx: the page is awaited
// directly, then the returned JSX is fed into renderWithProviders
// so provider context (Theme, QueryClient, Toast) wraps it.
// ---------------------------------------------------------------

describe("HomePage server component", () => {
  it("renders all three rails when every featured endpoint succeeds", async () => {
    server.use(
      http.get("*/api/v1/auctions/featured/ending-soon", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-000000000065", title: "Ending Parcel" })],
        }),
      ),
      http.get("*/api/v1/auctions/featured/just-listed", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-000000000066", title: "Fresh Parcel" })],
        }),
      ),
      http.get("*/api/v1/auctions/featured/most-active", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-000000000067", title: "Busy Parcel" })],
        }),
      ),
    );

    const page = await HomePage();
    renderWithProviders(page);

    expect(
      screen.getByRole("heading", { name: "Ending soon" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Featured this week" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Trending across regions" }),
    ).toBeInTheDocument();

    // Each rail surfaces its own card. Ending Parcel appears twice because
    // the Hero card stack also uses ending-soon listings as its preview.
    expect(screen.getAllByText("Ending Parcel").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("Fresh Parcel")).toBeInTheDocument();
    expect(screen.getByText("Busy Parcel")).toBeInTheDocument();

    // No placeholder branches should trigger on the happy path.
    expect(screen.queryByTestId("featured-row-empty")).toBeNull();
    expect(screen.queryByTestId("featured-row-unavailable")).toBeNull();
  });

  it("isolates a single failing rail — two healthy rails still render, the failing one shows the unavailable placeholder", async () => {
    server.use(
      http.get(
        "*/api/v1/auctions/featured/ending-soon",
        () => new HttpResponse(null, { status: 500 }),
      ),
      http.get("*/api/v1/auctions/featured/just-listed", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-0000000000c9", title: "Fresh Parcel" })],
        }),
      ),
      http.get("*/api/v1/auctions/featured/most-active", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-0000000000ca", title: "Busy Parcel" })],
        }),
      ),
    );

    const page = await HomePage();
    renderWithProviders(page);

    // The failing rail falls back to its neutral placeholder — proving
    // Promise.allSettled isolated the 500, not Promise.all short-circuit.
    expect(
      screen.getByText(/ending soon auctions are temporarily unavailable/i),
    ).toBeInTheDocument();

    // The other two rails render their content uninterrupted.
    expect(
      screen.getByRole("heading", { name: "Featured this week" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Fresh Parcel")).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Trending across regions" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Busy Parcel")).toBeInTheDocument();
  });
});
