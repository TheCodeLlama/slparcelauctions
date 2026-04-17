import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, within } from "@/test/render";
import type { AuctionStatus, SellerAuctionResponse } from "@/types/auction";
import { ListingSummaryRow } from "./ListingSummaryRow";

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

function baseAuction(
  overrides: Partial<SellerAuctionResponse> = {},
): SellerAuctionResponse {
  return {
    id: 42,
    sellerId: 1,
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
      description: "Beachfront parcel",
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: false,
      verifiedAt: null,
      lastChecked: null,
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "ACTIVE",
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

describe("ListingSummaryRow", () => {
  it("renders parcel name, status badge, and region/area line", () => {
    renderWithProviders(
      <ListingSummaryRow auction={baseAuction()} />,
      { auth: "authenticated" },
    );
    expect(screen.getByText("Beachfront parcel")).toBeInTheDocument();
    expect(
      screen.getByText(/Heterocera · 1024 m/),
    ).toBeInTheDocument();
    // Status badge
    expect(
      screen.getByText((t) => t.trim() === "Active"),
    ).toBeInTheDocument();
  });

  it("shows the em-dash bid fallback and 0 bidders when there is no bid", () => {
    renderWithProviders(
      <ListingSummaryRow auction={baseAuction({ currentHighBid: null, bidderCount: 0 })} />,
      { auth: "authenticated" },
    );
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.getByText(/0 bidders/)).toBeInTheDocument();
  });

  it("renders the bid total and singular bidder label when there is one bid", () => {
    renderWithProviders(
      <ListingSummaryRow auction={baseAuction({ currentHighBid: 750, bidderCount: 1 })} />,
      { auth: "authenticated" },
    );
    expect(screen.getByText("L$750")).toBeInTheDocument();
    expect(screen.getByText(/1 bidder(?!s)/)).toBeInTheDocument();
  });

  it.each([
    ["DRAFT" as AuctionStatus, ["Edit", "Continue"]],
    ["DRAFT_PAID" as AuctionStatus, ["Edit", "Continue"]],
    ["VERIFICATION_PENDING" as AuctionStatus, ["Continue"]],
    ["VERIFICATION_FAILED" as AuctionStatus, ["Continue"]],
  ])(
    "renders the pre-ACTIVE edit/continue actions when status=%s",
    (status, labels) => {
      renderWithProviders(
        <ListingSummaryRow auction={baseAuction({ status })} />,
        { auth: "authenticated" },
      );
      for (const label of labels) {
        expect(
          screen.getByRole("link", { name: new RegExp(`^${label}$`) }),
        ).toBeInTheDocument();
      }
      expect(
        screen.getByRole("button", { name: /More actions/ }),
      ).toBeInTheDocument();
    },
  );

  it("renders View listing for ACTIVE and hides cancel behind the overflow menu", () => {
    renderWithProviders(
      <ListingSummaryRow auction={baseAuction({ status: "ACTIVE" })} />,
      { auth: "authenticated" },
    );
    expect(
      screen.getByRole("link", { name: /View listing/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /More actions/ }),
    ).toBeInTheDocument();
  });

  it.each([
    "ENDED",
    "ESCROW_PENDING",
    "ESCROW_FUNDED",
    "TRANSFER_PENDING",
    "COMPLETED",
    "EXPIRED",
  ] as const)(
    "renders only View listing (no cancel menu) for %s",
    (status) => {
      renderWithProviders(
        <ListingSummaryRow auction={baseAuction({ status })} />,
        { auth: "authenticated" },
      );
      expect(
        screen.getByRole("link", { name: /View listing/ }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: /More actions/ }),
      ).not.toBeInTheDocument();
    },
  );

  it.each(["CANCELLED", "DISPUTED", "SUSPENDED"] as const)(
    "renders View details for archive-only status=%s",
    (status) => {
      renderWithProviders(
        <ListingSummaryRow auction={baseAuction({ status })} />,
        { auth: "authenticated" },
      );
      expect(
        screen.getByRole("link", { name: /View details/ }),
      ).toBeInTheDocument();
    },
  );

  it("renders the SUSPENDED callout only for suspended rows", () => {
    const { rerender } = renderWithProviders(
      <ListingSummaryRow auction={baseAuction({ status: "ACTIVE" })} />,
      { auth: "authenticated" },
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    rerender(<ListingSummaryRow auction={baseAuction({ status: "SUSPENDED" })} />);
    const alert = screen.getByRole("alert");
    expect(
      within(alert).getByText(/Listing suspended/i),
    ).toBeInTheDocument();
  });

  it("falls back to parcel snapshotUrl when there are no photos", () => {
    renderWithProviders(
      <ListingSummaryRow
        auction={baseAuction({
          photos: [],
          parcel: {
            ...baseAuction().parcel,
            snapshotUrl: "https://snap.example/p/1.png",
          },
        })}
      />,
      { auth: "authenticated" },
    );
    const img = document.querySelector(
      'img[src="https://snap.example/p/1.png"]',
    );
    expect(img).not.toBeNull();
  });
});
