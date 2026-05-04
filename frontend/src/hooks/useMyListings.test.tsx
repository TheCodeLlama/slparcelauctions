import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import { makeWrapper } from "@/test/render";
import { server } from "@/test/msw/server";
import type { AuctionStatus, SellerAuctionResponse } from "@/types/auction";
import {
  useMyListings,
  useMyListingsSuspendedCount,
} from "./useMyListings";

function row(
  id: number,
  status: AuctionStatus,
  overrides: Partial<SellerAuctionResponse> = {},
): SellerAuctionResponse {
  const publicId = `00000000-0000-0000-0000-${String(id).padStart(12, "0")}`;
  return {
    publicId,
    sellerPublicId: "00000000-0000-0000-0000-000000000001",
    title: "Featured Parcel Listing",
    parcel: {
      slParcelUuid: `00000000-0000-0000-0000-00000000000${id}`,
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

function seed(rows: SellerAuctionResponse[]) {
  server.use(
    http.get("*/api/v1/users/me/auctions", () => HttpResponse.json(rows)),
  );
}

describe("useMyListings", () => {
  it("returns all rows and the all-bucket when filter=All", async () => {
    seed([row(1, "ACTIVE"), row(2, "DRAFT")]);
    const { result } = renderHook(() => useMyListings("All"), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.listings).toHaveLength(2);
    expect(result.current.all).toHaveLength(2);
  });

  it("filters client-side to the Active bucket", async () => {
    seed([row(1, "ACTIVE"), row(2, "DRAFT"), row(3, "ACTIVE")]);
    const { result } = renderHook(() => useMyListings("Active"), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.listings.map((a) => a.publicId).sort()).toEqual([
      "00000000-0000-0000-0000-000000000001",
      "00000000-0000-0000-0000-000000000003",
    ]);
  });

  it("collapses all four draft-bucket statuses into the Drafts filter", async () => {
    seed([
      row(1, "DRAFT"),
      row(2, "DRAFT_PAID"),
      row(3, "VERIFICATION_PENDING"),
      row(4, "VERIFICATION_FAILED"),
      row(5, "ACTIVE"),
    ]);
    const { result } = renderHook(() => useMyListings("Drafts"), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.listings.map((a) => a.publicId).sort()).toEqual([
      "00000000-0000-0000-0000-000000000001",
      "00000000-0000-0000-0000-000000000002",
      "00000000-0000-0000-0000-000000000003",
      "00000000-0000-0000-0000-000000000004",
    ]);
  });

  it("collapses the six Ended-ish statuses into the Ended filter", async () => {
    seed([
      row(1, "ENDED"),
      row(2, "ESCROW_PENDING"),
      row(3, "ESCROW_FUNDED"),
      row(4, "TRANSFER_PENDING"),
      row(5, "COMPLETED"),
      row(6, "EXPIRED"),
      row(7, "ACTIVE"),
    ]);
    const { result } = renderHook(() => useMyListings("Ended"), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.listings.map((a) => a.publicId).sort()).toEqual([
      "00000000-0000-0000-0000-000000000001",
      "00000000-0000-0000-0000-000000000002",
      "00000000-0000-0000-0000-000000000003",
      "00000000-0000-0000-0000-000000000004",
      "00000000-0000-0000-0000-000000000005",
      "00000000-0000-0000-0000-000000000006",
    ]);
  });

  it("only shows SUSPENDED rows in the Suspended filter", async () => {
    seed([row(1, "SUSPENDED"), row(2, "ACTIVE"), row(3, "CANCELLED")]);
    const { result } = renderHook(() => useMyListings("Suspended"), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.listings).toHaveLength(1);
    expect(result.current.listings[0].publicId).toBe(
      "00000000-0000-0000-0000-000000000001",
    );
  });
});

describe("useMyListingsSuspendedCount", () => {
  it("returns 0 when there are no suspended listings", async () => {
    seed([row(1, "ACTIVE"), row(2, "DRAFT")]);
    const { result } = renderHook(() => useMyListingsSuspendedCount(), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });
    await waitFor(() => expect(result.current).toBeDefined());
    // Wait for data to land.
    await waitFor(() => expect(result.current).toBe(0));
  });

  it("counts suspended listings", async () => {
    seed([
      row(1, "SUSPENDED"),
      row(2, "SUSPENDED"),
      row(3, "ACTIVE"),
    ]);
    const { result } = renderHook(() => useMyListingsSuspendedCount(), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });
    await waitFor(() => expect(result.current).toBe(2));
  });
});
