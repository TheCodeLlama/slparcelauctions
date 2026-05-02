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
import type { PublicAuctionResponse } from "@/types/auction";

export function fakePublicAuction(
  overrides: Partial<PublicAuctionResponse> = {},
): PublicAuctionResponse {
  return {
    id: 7,
    sellerId: 42,
    title: "Featured Parcel Listing",
    parcel: {
      id: 1,
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
