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
    publicId: "00000000-0000-0000-0000-00000000002a",
    sellerPublicId: "00000000-0000-0000-0000-000000000001",
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
    winnerPublicId: null,
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
    // Parcel name is demoted to the secondary line once a title is set.
    expect(screen.getByTestId("listing-summary-secondary")).toHaveTextContent(
      /Beachfront parcel/,
    );
    expect(
      screen.getByText(/Heterocera · 1024 m/),
    ).toBeInTheDocument();
    // Status badge
    expect(
      screen.getByText((t) => t.trim() === "Active"),
    ).toBeInTheDocument();
  });

  it("shows auction title as primary label, parcel name as secondary", () => {
    renderWithProviders(
      <ListingSummaryRow
        auction={baseAuction({
          title: "Premium Waterfront",
          parcel: {
            ...baseAuction().parcel,
            description: "Bayside Cottage",
            regionName: "Tula",
          },
        })}
      />,
      { auth: "authenticated" },
    );
    expect(screen.getByTestId("listing-summary-primary")).toHaveTextContent(
      "Premium Waterfront",
    );
    expect(screen.getByTestId("listing-summary-secondary")).toHaveTextContent(
      "Tula",
    );
  });

  it("shows the em-dash bid fallback and 0 bids when there is no bid on ACTIVE", () => {
    renderWithProviders(
      <ListingSummaryRow auction={baseAuction({ currentHighBid: null, bidCount: 0 })} />,
      { auth: "authenticated" },
    );
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.getByText(/0 bids/)).toBeInTheDocument();
  });

  it("renders the bid total and singular bid label when there is one bid on ACTIVE", () => {
    renderWithProviders(
      <ListingSummaryRow auction={baseAuction({ currentHighBid: 750, bidCount: 1 })} />,
      { auth: "authenticated" },
    );
    expect(screen.getByText("L$750")).toBeInTheDocument();
    expect(screen.getByText(/1 bid(?!s)/)).toBeInTheDocument();
  });

  it("renders countdown time when ACTIVE and endsAt is in the future", () => {
    const endsAt = new Date(Date.now() + 2 * 3600_000).toISOString();
    renderWithProviders(
      <ListingSummaryRow
        auction={baseAuction({
          currentHighBid: 42500,
          bidCount: 12,
          endsAt,
        })}
      />,
      { auth: "authenticated" },
    );
    expect(screen.getByText("L$42,500")).toBeInTheDocument();
    expect(screen.getByText(/12 bids/)).toBeInTheDocument();
    // CountdownTimer renders with role="timer"
    expect(screen.getByRole("timer")).toBeInTheDocument();
  });

  it("renders 'Sold for L$X to @winner' for ENDED + SOLD", () => {
    renderWithProviders(
      <ListingSummaryRow
        auction={
          baseAuction({
            status: "ENDED",
            currentHighBid: 48000,
            bidCount: 4,
            reservePrice: 30000,
            winnerPublicId: "00000000-0000-0000-0000-000000000037",
            // winnerDisplayName is on the widened shape; cast so TS accepts.
            ...({
              endOutcome: "SOLD",
              finalBidAmount: 48000,
              winnerDisplayName: "WinningBidder",
            } as Partial<SellerAuctionResponse>)
          })
        }
      />,
      { auth: "authenticated" },
    );
    expect(screen.getByText(/Sold for/)).toBeInTheDocument();
    expect(screen.getByText("L$48,000")).toBeInTheDocument();
    expect(screen.getByText("@WinningBidder")).toBeInTheDocument();
  });

  it("renders 'reserve not met' sub-line for ENDED + RESERVE_NOT_MET", () => {
    renderWithProviders(
      <ListingSummaryRow
        auction={
          baseAuction({
            status: "ENDED",
            currentHighBid: 12000,
            bidCount: 2,
            reservePrice: 30000,
            ...({ endOutcome: "RESERVE_NOT_MET" } as Partial<SellerAuctionResponse>)
          })
        }
      />,
      { auth: "authenticated" },
    );
    expect(
      screen.getByText(/Ended — reserve not met \(highest bid/),
    ).toBeInTheDocument();
    expect(screen.getByText("L$12,000")).toBeInTheDocument();
  });

  it("renders 'Ended with no bids' for ENDED + NO_BIDS", () => {
    renderWithProviders(
      <ListingSummaryRow
        auction={
          baseAuction({
            status: "ENDED",
            currentHighBid: null,
            bidCount: 0,
            ...({ endOutcome: "NO_BIDS" } as Partial<SellerAuctionResponse>)
          })
        }
      />,
      { auth: "authenticated" },
    );
    expect(screen.getByText("Ended with no bids")).toBeInTheDocument();
  });

  it("omits the sub-line for DRAFT and CANCELLED rows", () => {
    const { rerender } = renderWithProviders(
      <ListingSummaryRow
        auction={baseAuction({
          status: "DRAFT",
          currentHighBid: null,
          bidCount: 0,
        })}
      />,
      { auth: "authenticated" },
    );
    // No sub-line text about bids / reserve / "no bids"
    expect(screen.queryByText(/bids/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Sold for/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Ended/)).not.toBeInTheDocument();

    rerender(
      <ListingSummaryRow
        auction={baseAuction({ status: "CANCELLED" })}
      />,
    );
    expect(screen.queryByText(/bids/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Sold for/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Ended/)).not.toBeInTheDocument();
  });

  it("renders Sold for ENDED + SOLD when DTO explicitly sets endOutcome", () => {
    renderWithProviders(
      <ListingSummaryRow
        auction={
          baseAuction({
            status: "ENDED",
            currentHighBid: 5000,
            bidCount: 3,
            reservePrice: 1000,
            buyNowPrice: null,
            winnerPublicId: "00000000-0000-0000-0000-000000000063",
            ...({
              endOutcome: "SOLD",
              finalBidAmount: 5000,
              winnerDisplayName: "Buyer",
            } as Partial<SellerAuctionResponse>)
          })
        }
      />,
      { auth: "authenticated" },
    );
    expect(screen.getByText(/Sold for/)).toBeInTheDocument();
    expect(screen.getByText("L$5,000")).toBeInTheDocument();
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
      // ENDED rows need an endOutcome per backend invariant (Epic 05
      // sub-spec 1). Non-ENDED statuses ignore the field but we set it
      // uniformly so the it.each fixture is consistent.
      renderWithProviders(
        <ListingSummaryRow
          auction={baseAuction({
            status,
            bidCount: 0,
            ...({ endOutcome: "NO_BIDS" } as Partial<SellerAuctionResponse>),
          })}
        />,
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

  it("renders escrow chip + view-escrow link when escrowState is set", () => {
    const auction = baseAuction({
      status: "ESCROW_PENDING",
      ...({
        escrowState: "ESCROW_PENDING",
        transferConfirmedAt: null,
      } as Partial<SellerAuctionResponse>),
    });
    renderWithProviders(<ListingSummaryRow auction={auction} />, {
      auth: "authenticated",
    });
    expect(screen.getByText(/awaiting payment/i)).toBeInTheDocument();
    const link = screen.getByRole("link", { name: /view escrow/i });
    expect(link).toHaveAttribute("href", `/auction/${auction.publicId}/escrow`);
  });

  it("keeps existing view-listing link when escrowState is null", () => {
    const auction = baseAuction({
      status: "ENDED",
      bidCount: 0,
      ...({ endOutcome: "NO_BIDS" } as Partial<SellerAuctionResponse>),
    });
    renderWithProviders(<ListingSummaryRow auction={auction} />, {
      auth: "authenticated",
    });
    expect(screen.queryByText(/awaiting payment/i)).not.toBeInTheDocument();
    const link = screen.getByRole("link", { name: /view listing/i });
    expect(link).toHaveAttribute("href", `/auction/${auction.publicId}`);
  });
});
