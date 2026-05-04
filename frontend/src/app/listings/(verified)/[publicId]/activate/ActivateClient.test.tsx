import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type { SellerAuctionResponse } from "@/types/auction";
import { ActivateClient } from "./ActivateClient";

const push = vi.fn();
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push,
    replace,
    back: vi.fn(),
    refresh: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/listings/42/activate",
  useSearchParams: () => new URLSearchParams(),
}));

function auctionBase(
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
    winnerPublicId: null,
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

describe("ActivateClient", () => {
  beforeEach(() => {
    push.mockReset();
    replace.mockReset();
    server.use(
      http.get("*/api/v1/config/listing-fee", () =>
        HttpResponse.json({ amountLindens: 100 }),
      ),
    );
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("DRAFT → renders the listing preview + activate panel", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a", () =>
        HttpResponse.json(auctionBase({ status: "DRAFT" })),
      ),
      http.get("*/api/v1/me/wallet", () =>
        HttpResponse.json({
          balance: 500,
          reserved: 0,
          available: 500,
          penaltyOwed: 0,
          queuedForWithdrawal: 0,
          termsAccepted: true,
          termsVersion: "v1.0",
          termsAcceptedAt: "2026-04-17T00:00:00Z",
          recentLedger: [],
        }),
      ),
    );
    renderWithProviders(<ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "authenticated",
    });
    expect(
      await screen.findByText(/Preview — this is how your listing/i),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: /Activate Listing/i }),
    ).toBeInTheDocument();
  });

  it("DRAFT_PAID → click a method → shows the in-progress panel", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a", () => {
        calls += 1;
        if (calls < 2) {
          return HttpResponse.json(auctionBase({ status: "DRAFT_PAID" }));
        }
        return HttpResponse.json(
          auctionBase({
            status: "VERIFICATION_PENDING",
            verificationMethod: "UUID_ENTRY",
            pendingVerification: null,
          }),
        );
      }),
      http.put("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a/verify", () =>
        HttpResponse.json(
          auctionBase({
            status: "VERIFICATION_PENDING",
            verificationMethod: "UUID_ENTRY",
            pendingVerification: null,
          }),
        ),
      ),
    );
    renderWithProviders(<ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "authenticated",
    });

    const buttons = await screen.findAllByRole("button", {
      name: /Use this method/i,
    });
    await userEvent.click(buttons[0]);

    await waitFor(() =>
      expect(
        screen.getByText(/Checking ownership with the Second Life World API/i),
      ).toBeInTheDocument(),
    );
  });

  it("VERIFICATION_FAILED renders the retry banner with the failure notes", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a", () =>
        HttpResponse.json(
          auctionBase({
            status: "VERIFICATION_FAILED",
            verificationMethod: "UUID_ENTRY",
            verificationNotes:
              "Ownership check failed: SL API returned a different owner.",
            pendingVerification: null,
          }),
        ),
      ),
    );
    renderWithProviders(<ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "authenticated",
    });
    expect(
      await screen.findByText(/Ownership check failed/i),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/no additional fee needed/i),
    ).toBeInTheDocument();
  });

  it("ACTIVE renders the success screen with both actions", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a", () =>
        HttpResponse.json(auctionBase({ status: "ACTIVE" })),
      ),
    );
    renderWithProviders(<ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "authenticated",
    });
    expect(
      await screen.findByText(/Your listing is live/i),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Back to My Listings/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /View public listing/i }),
    ).toBeInTheDocument();
  });

  it("CANCELLED redirects to the dashboard listings tab", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a", () =>
        HttpResponse.json(auctionBase({ status: "CANCELLED" })),
      ),
    );
    renderWithProviders(<ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "authenticated",
    });
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith("/dashboard/listings"),
    );
  });
});
