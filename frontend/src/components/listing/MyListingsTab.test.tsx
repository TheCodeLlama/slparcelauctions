import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type { AuctionStatus, SellerAuctionResponse } from "@/types/auction";
import { MyListingsTab } from "./MyListingsTab";

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    back: vi.fn(),
    refresh: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/dashboard/listings",
  useSearchParams: () => new URLSearchParams(),
}));

function row(
  id: number,
  status: AuctionStatus,
  overrides: Partial<SellerAuctionResponse> = {},
): SellerAuctionResponse {
  return {
    id,
    sellerId: 1,
    title: `Parcel ${id}`,
    parcel: {
      id,
      slParcelUuid: `00000000-0000-0000-0000-00000000000${id}`,
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
      description: `Parcel ${id}`,
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: false,
      verifiedAt: null,
      lastChecked: null,
      createdAt: "2026-04-17T00:00:00Z",
    },
    status,
    verificationMethod: null,
    verificationTier: null,
    pendingVerification: null,
    verificationNotes: null,
    startingBid: 500,
    reservePrice: null,
    buyNowPrice: null,
    currentBid: null,
    bidCount: 0,
    currentHighBid: null,
    bidderCount: 0,
    winnerId: null,
    durationHours: 72,
    snipeProtect: false,
    snipeWindowMin: null,
    startsAt: null,
    endsAt: null,
    originalEndsAt: null,
    sellerDesc: null,
    tags: [],
    photos: [],
    listingFeePaid: false,
    listingFeeAmt: null,
    listingFeeTxn: null,
    listingFeePaidAt: null,
    commissionRate: 0.05,
    commissionAmt: null,
    createdAt: "2026-04-17T00:00:00Z",
    updatedAt: "2026-04-17T00:00:00Z",
    ...overrides,
  };
}

function seed(rows: SellerAuctionResponse[]) {
  server.use(
    http.get("*/api/v1/users/me/auctions", () => HttpResponse.json(rows)),
  );
}

describe("MyListingsTab", () => {
  it("shows the first-listing empty state + CTA when seller has no listings", async () => {
    seed([]);
    renderWithProviders(<MyListingsTab />, { auth: "authenticated" });
    expect(
      await screen.findByRole("heading", { name: /No listings yet/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /Create your first listing/ }),
    ).toBeInTheDocument();
  });

  it("renders the listings, header count, and filter chips when rows exist", async () => {
    seed([row(1, "ACTIVE"), row(2, "DRAFT")]);
    renderWithProviders(<MyListingsTab />, { auth: "authenticated" });
    expect(
      await screen.findByRole("heading", { name: /My Listings/ }),
    ).toBeInTheDocument();
    // Header count (2)
    expect(screen.getByText(/\(2\)/)).toBeInTheDocument();
    // Create CTA in the header
    expect(
      screen.getByRole("link", { name: /Create new listing/ }),
    ).toBeInTheDocument();
    // Filter chips render
    expect(screen.getByRole("tab", { name: /All/ })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /Active/ })).toBeInTheDocument();
    // Both rows render
    expect(screen.getByText("Parcel 1")).toBeInTheDocument();
    expect(screen.getByText("Parcel 2")).toBeInTheDocument();
  });

  it("filters the visible rows when a chip is clicked", async () => {
    seed([row(1, "ACTIVE"), row(2, "DRAFT")]);
    renderWithProviders(<MyListingsTab />, { auth: "authenticated" });
    await screen.findByText("Parcel 1");
    await userEvent.click(screen.getByRole("tab", { name: /Drafts/ }));
    await waitFor(() => {
      expect(screen.queryByText("Parcel 1")).not.toBeInTheDocument();
      expect(screen.getByText("Parcel 2")).toBeInTheDocument();
    });
  });

  it("shows the in-bucket empty placeholder when the filter has no matches", async () => {
    seed([row(1, "ACTIVE")]);
    renderWithProviders(<MyListingsTab />, { auth: "authenticated" });
    await screen.findByText("Parcel 1");
    await userEvent.click(screen.getByRole("tab", { name: /Drafts/ }));
    expect(
      await screen.findByText(/No listings in this filter/),
    ).toBeInTheDocument();
  });

  it("renders the Suspended chip when at least one suspended listing exists", async () => {
    seed([row(1, "ACTIVE"), row(2, "SUSPENDED")]);
    renderWithProviders(<MyListingsTab />, { auth: "authenticated" });
    await screen.findByText("Parcel 1");
    expect(
      screen.getByRole("tab", { name: /Suspended/ }),
    ).toBeInTheDocument();
  });

  it("hides the Suspended chip when no listings are suspended", async () => {
    seed([row(1, "ACTIVE"), row(2, "DRAFT")]);
    renderWithProviders(<MyListingsTab />, { auth: "authenticated" });
    await screen.findByText("Parcel 1");
    expect(
      screen.queryByRole("tab", { name: /Suspended/ }),
    ).not.toBeInTheDocument();
  });
});
