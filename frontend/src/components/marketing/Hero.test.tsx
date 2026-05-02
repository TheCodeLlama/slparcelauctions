// frontend/src/components/marketing/Hero.test.tsx

import { describe, it, expect, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { Hero } from "./Hero";
import type { AuctionSearchResultDto } from "@/types/search";

function sampleListing(
  overrides: Partial<AuctionSearchResultDto> = {},
): AuctionSearchResultDto {
  return {
    id: 1,
    title: "Sample Parcel",
    status: "ACTIVE",
    endOutcome: null,
    parcel: {
      id: 11,
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
      tags: [],
    },
    primaryPhotoUrl: null,
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

describe("Hero", () => {
  beforeEach(() => {
    // Default state: unauthenticated. The setup file already registers
    // refreshUnauthenticated as the default; explicit here for clarity.
    server.use(authHandlers.refreshUnauthenticated());
  });

  it("renders the headline and primary CTA", async () => {
    renderWithProviders(<Hero featured={[]} />);
    expect(
      screen.getByRole("heading", { name: /auction parcels with real escrow protection/i })
    ).toBeInTheDocument();

    const browseLink = screen.getByRole("link", { name: /browse auctions/i });
    expect(browseLink).toHaveAttribute("href", "/browse");
  });

  it("renders 'Start selling → /register' for unauthenticated users", async () => {
    renderWithProviders(<Hero featured={[]} />);
    await waitFor(() => {
      const startSellingLink = screen.getByRole("link", { name: /start selling/i });
      expect(startSellingLink).toHaveAttribute("href", "/register");
    });
  });

  it("renders 'List your parcel → /listings/new' for authenticated users", async () => {
    server.use(authHandlers.refreshSuccess());
    renderWithProviders(<Hero featured={[]} />);
    await waitFor(() => {
      const listLink = screen.getByRole("link", { name: /list your parcel/i });
      expect(listLink).toHaveAttribute("href", "/listings/new");
    });
  });

  it("renders the eyebrow tagline", () => {
    renderWithProviders(<Hero featured={[]} />);
    expect(screen.getByText(/the marketplace for virtual land/i)).toBeInTheDocument();
  });

  it("renders featured card stack when listings are provided", () => {
    const listings = [
      sampleListing({ id: 1, title: "Alpha Parcel" }),
      sampleListing({ id: 2, title: "Beta Parcel" }),
    ];
    renderWithProviders(<Hero featured={listings} />);
    // Cards render as links to auction detail pages
    expect(screen.getByRole("link", { name: /alpha parcel/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /beta parcel/i })).toBeInTheDocument();
  });
});
