import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import type {
  PendingVerification,
  SellerAuctionResponse,
} from "@/types/auction";
import { VerificationInProgressPanel } from "./VerificationInProgressPanel";

function makeAuction(
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
      description: null,
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: false,
      verifiedAt: null,
      lastChecked: null,
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "VERIFICATION_PENDING",
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

const PENDING_SALE: PendingVerification = {
  method: "SALE_TO_BOT",
  code: null,
  codeExpiresAt: null,
  botTaskId: 7,
  instructions: null,
};

describe("VerificationInProgressPanel", () => {
  it("dispatches to UUID entry", () => {
    renderWithProviders(
      <VerificationInProgressPanel
        auction={makeAuction({ verificationMethod: "UUID_ENTRY" })}
      />,
    );
    expect(
      screen.getByText(/Checking ownership with the Second Life World API/i),
    ).toBeInTheDocument();
  });

  it("dispatches to Sale-to-bot when the pending payload is present", () => {
    renderWithProviders(
      <VerificationInProgressPanel
        auction={makeAuction({
          verificationMethod: "SALE_TO_BOT",
          pendingVerification: PENDING_SALE,
        })}
      />,
    );
    // Sale-to-bot panel includes the account name in the heading and
    // in the list — either match is enough to confirm dispatch.
    expect(screen.getAllByText(/SLPAEscrow Resident/).length).toBeGreaterThan(0);
  });

  it("renders a starting spinner when the method is set but pending payload is not yet available", () => {
    renderWithProviders(
      <VerificationInProgressPanel
        auction={makeAuction({
          verificationMethod: "REZZABLE",
          pendingVerification: null,
        })}
      />,
    );
    expect(screen.getByText(/Starting verification/i)).toBeInTheDocument();
  });
});
