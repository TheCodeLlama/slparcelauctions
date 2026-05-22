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
    primaryPhotoLightUrl: null,
    primaryPhotoDarkUrl: null,
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
// Tests — HomePage server component invocation pattern mirrors
// app/auction/[id]/page.integration.test.tsx: the page is awaited
// directly, then the returned JSX is fed into renderWithProviders
// so provider context (Theme, QueryClient, Toast) wraps it.
// ---------------------------------------------------------------

describe("HomePage server component", () => {
  it("renders all three rails when every endpoint succeeds", async () => {
    server.use(
      http.get("*/api/v1/auctions/rails/featured", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-000000000065", title: "Featured Parcel" })],
        }),
      ),
      http.get("*/api/v1/auctions/rails/ending-soon", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-000000000066", title: "Ending Parcel" })],
        }),
      ),
      http.get("*/api/v1/auctions/rails/trending", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-000000000067", title: "Trending Parcel" })],
        }),
      ),
    );

    const page = await HomePage();
    renderWithProviders(page);

    expect(
      screen.getByRole("heading", { name: "Featured" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Ending soon" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Trending" }),
    ).toBeInTheDocument();

    // Old titles must be gone.
    expect(
      screen.queryByRole("heading", { name: "Featured this week" }),
    ).toBeNull();
    expect(
      screen.queryByRole("heading", { name: "Trending across regions" }),
    ).toBeNull();

    // Featured Parcel appears at least once — Hero stack also pulls
    // from the Featured rail in the new layout.
    expect(screen.getAllByText("Featured Parcel").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("Ending Parcel")).toBeInTheDocument();
    expect(screen.getByText("Trending Parcel")).toBeInTheDocument();

    // No placeholder branches should trigger on the happy path.
    expect(screen.queryByTestId("featured-row-empty")).toBeNull();
    expect(screen.queryByTestId("featured-row-unavailable")).toBeNull();
  });

  it("hides the Ending Soon section when its content is empty", async () => {
    server.use(
      http.get("*/api/v1/auctions/rails/featured", () =>
        HttpResponse.json({
          content: [sampleListing({ title: "Featured Parcel" })],
        }),
      ),
      http.get("*/api/v1/auctions/rails/ending-soon", () =>
        HttpResponse.json({ content: [] }),
      ),
      http.get("*/api/v1/auctions/rails/trending", () =>
        HttpResponse.json({
          content: [sampleListing({ title: "Trending Parcel" })],
        }),
      ),
    );

    const page = await HomePage();
    renderWithProviders(page);

    expect(screen.queryByRole("heading", { name: "Ending soon" })).toBeNull();
    expect(screen.getByRole("heading", { name: "Featured" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Trending" })).toBeInTheDocument();
  });

  it("renders the Ending Soon unavailable placeholder when its fetch is rejected", async () => {
    server.use(
      http.get("*/api/v1/auctions/rails/featured", () =>
        HttpResponse.json({
          content: [sampleListing({ title: "Featured Parcel" })],
        }),
      ),
      http.get(
        "*/api/v1/auctions/rails/ending-soon",
        () => new HttpResponse(null, { status: 500 }),
      ),
      http.get("*/api/v1/auctions/rails/trending", () =>
        HttpResponse.json({
          content: [sampleListing({ title: "Trending Parcel" })],
        }),
      ),
    );

    const page = await HomePage();
    renderWithProviders(page);

    // Rejected (vs fulfilled-empty) still surfaces the placeholder.
    expect(
      screen.getByRole("heading", { name: "Ending soon" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/ending soon auctions are temporarily unavailable/i),
    ).toBeInTheDocument();
  });

  it("isolates a single failing rail — two healthy rails still render", async () => {
    server.use(
      http.get(
        "*/api/v1/auctions/rails/featured",
        () => new HttpResponse(null, { status: 500 }),
      ),
      http.get("*/api/v1/auctions/rails/ending-soon", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-0000000000c9", title: "Ending Parcel" })],
        }),
      ),
      http.get("*/api/v1/auctions/rails/trending", () =>
        HttpResponse.json({
          content: [sampleListing({ publicId: "00000000-0000-0000-0000-0000000000ca", title: "Trending Parcel" })],
        }),
      ),
    );

    const page = await HomePage();
    renderWithProviders(page);

    expect(
      screen.getByText(/featured auctions are temporarily unavailable/i),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Ending soon" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Ending Parcel")).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Trending" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Trending Parcel")).toBeInTheDocument();
  });
});
