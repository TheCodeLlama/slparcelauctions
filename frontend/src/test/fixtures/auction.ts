// frontend/src/test/fixtures/auction.ts
//
// Shared fixture builder for PublicAuctionResponse used by MSW handlers in
// integration tests. Mirrors the inline helper in
// `src/app/auction/[id]/page.integration.test.tsx` — extracted here so the
// escrow RSC shell test (and future tests) can reuse a fully-populated
// response without duplicating the field list.
//
// The backend DTO has no runtime parser on the client (getAuction simply
// returns response.json()), so a partial object would technically work — but
// every consumer builds against the type, so keeping the fixture complete
// avoids spread-override footguns when tests need to tweak one field.
import type {
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";

/**
 * Canonical enriched seller block. Mirrors the backend
 * {@code PublicAuctionResponse.SellerSummary} JSON shape (Epic 07 sub-spec 1
 * Task 2) that both the public and seller-facing auction responses now
 * carry. Kept here so every fixture consumer (activate / draft-editor
 * tests, auction detail tests) asserts against one canonical shape.
 */
export const fakeAuctionSeller = {
  publicId: "11111111-1111-1111-1111-111111111111",
  displayName: "Test Seller",
  avatarUrl: null,
  averageRating: 4.5,
  reviewCount: 12,
  completedSales: 7,
  completionRate: 0.92,
  memberSince: "2025-11-03",
} as const;

/**
 * Shared seller-facing auction fixture. Mirrors the backend
 * {@code SellerAuctionResponse} returned by the seller-scoped
 * {@code GET /api/v1/auctions/{publicId}} (the activate / draft-editor
 * flow). Carries the enriched {@code seller} block so the draft preview
 * renders the real seller card instead of the "You" placeholder.
 */
export function fakeSellerAuction(
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
    verificationTier: null,
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
    seller: { ...fakeAuctionSeller },
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

export function fakePublicAuction(
  overrides: Partial<PublicAuctionResponse> = {},
): PublicAuctionResponse {
  return {
    publicId: "00000000-0000-0000-0000-000000000007",
    sellerPublicId: "00000000-0000-0000-0000-00000000002a",
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
      verified: true,
      verifiedAt: "2026-04-20T00:00:00Z",
      lastChecked: "2026-04-20T00:00:00Z",
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "ACTIVE",
    verificationTier: "SCRIPT",
    startingBid: 500,
    hasReserve: false,
    reserveMet: true,
    buyNowPrice: null,
    currentBid: 1500,
    bidCount: 3,
    currentHighBid: 1500,
    bidderCount: 2,
    durationHours: 72,
    snipeProtect: true,
    snipeWindowMin: 10,
    startsAt: "2026-04-19T00:00:00Z",
    endsAt: "2026-04-22T00:00:00Z",
    originalEndsAt: "2026-04-22T00:00:00Z",
    sellerDesc: null,
    tags: [],
    photos: [],
    ...overrides,
  };
}
