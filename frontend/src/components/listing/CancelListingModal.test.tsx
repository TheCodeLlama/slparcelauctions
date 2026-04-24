import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type { SellerAuctionResponse } from "@/types/auction";
import { CancelListingModal } from "./CancelListingModal";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push,
    replace: vi.fn(),
    back: vi.fn(),
    refresh: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/listings/42/activate",
  useSearchParams: () => new URLSearchParams(),
}));

function baseAuction(
  overrides: Partial<SellerAuctionResponse> = {},
): SellerAuctionResponse {
  return {
    id: 42,
    sellerId: 1,
    title: "Featured Parcel Listing",
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
    status: "DRAFT_PAID",
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
    snipeProtect: true,
    snipeWindowMin: 10,
    startsAt: null,
    endsAt: null,
    originalEndsAt: null,
    sellerDesc: null,
    tags: [],
    photos: [],
    listingFeePaid: true,
    listingFeeAmt: 100,
    listingFeeTxn: null,
    listingFeePaidAt: null,
    commissionRate: 0.05,
    commissionAmt: null,
    createdAt: "2026-04-17T00:00:00Z",
    updatedAt: "2026-04-17T00:00:00Z",
    ...overrides,
  };
}

describe("CancelListingModal", () => {
  it("renders the refund copy derived from the auction status", () => {
    renderWithProviders(
      <CancelListingModal
        open
        onClose={vi.fn()}
        auction={baseAuction({ status: "DRAFT_PAID", listingFeeAmt: 100 })}
      />,
      { auth: "authenticated" },
    );
    expect(
      screen.getByText(/Refund: L\$100.*full refund/i),
    ).toBeInTheDocument();
  });

  it("shows the ACTIVE-forfeit copy when cancelling an active listing", () => {
    renderWithProviders(
      <CancelListingModal
        open
        onClose={vi.fn()}
        auction={baseAuction({ status: "ACTIVE" })}
      />,
      { auth: "authenticated" },
    );
    expect(
      screen.getByText(
        /No refund — cancelling an active listing does not refund the fee/i,
      ),
    ).toBeInTheDocument();
  });

  it("posts the cancel and redirects on success", async () => {
    push.mockClear();
    let received: { reason?: string } | null = null;
    server.use(
      http.put("*/api/v1/auctions/42/cancel", async ({ request }) => {
        received = (await request.json()) as { reason?: string };
        return HttpResponse.json(baseAuction({ status: "CANCELLED" }));
      }),
    );
    const onClose = vi.fn();
    renderWithProviders(
      <CancelListingModal
        open
        onClose={onClose}
        auction={baseAuction()}
      />,
      { auth: "authenticated" },
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Cancel listing/i }),
    );
    await waitFor(() => {
      expect(received).not.toBeNull();
      // No reason entered → body should be `{}`, not `{ reason: "" }`.
      expect(received?.reason).toBeUndefined();
    });
    await waitFor(() => expect(push).toHaveBeenCalledWith("/dashboard/listings"));
    expect(onClose).toHaveBeenCalled();
  });
});
