import { describe, expect, it, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor, act } from "@testing-library/react";
import { server } from "@/test/msw/server";
import { makeWrapper } from "@/test/render";
import type { ParcelDto } from "@/types/parcel";
import type { SellerAuctionResponse } from "@/types/auction";
import { useListingDraft } from "./useListingDraft";

const VALID_UUID = "00000000-0000-0000-0000-000000000001";

const sampleParcel: ParcelDto = {
  id: 42,
  slParcelUuid: VALID_UUID,
  ownerUuid: "aaaa1111-0000-0000-0000-000000000000",
  ownerType: "agent",
  regionName: "Heterocera",
  gridX: 1000,
  gridY: 1000,
  continentName: "Heterocera",
  areaSqm: 1024,
  description: "Beachfront retreat",
  snapshotUrl: null,
  slurl: "secondlife://Heterocera/128/128/25",
  maturityRating: "GENERAL",
  verified: false,
  verifiedAt: null,
  lastChecked: null,
  createdAt: "2026-04-17T00:00:00Z",
};

function sellerResponse(
  overrides: Partial<SellerAuctionResponse> = {},
): SellerAuctionResponse {
  return {
    id: 7,
    sellerId: 42,
    parcel: sampleParcel,
    status: "DRAFT",
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

describe("useListingDraft", () => {
  beforeEach(() => {
    // sessionStorage persists across tests in jsdom — clear it so each
    // test starts with a clean persistence slot and hydration doesn't
    // pick up stale state from a sibling test.
    if (typeof window !== "undefined") {
      window.sessionStorage.clear();
    }
  });

  it("creates an auction on first save and updates on second save", async () => {
    let created = 0;
    let updated = 0;
    let refetched = 0;
    server.use(
      http.post("*/api/v1/auctions", () => {
        created += 1;
        return HttpResponse.json(
          sellerResponse({ id: 7, startingBid: 500 }),
          { status: 201 },
        );
      }),
      http.put("*/api/v1/auctions/7", () => {
        updated += 1;
        return HttpResponse.json(
          sellerResponse({ id: 7, startingBid: 750 }),
        );
      }),
      http.get("*/api/v1/auctions/7", () => {
        refetched += 1;
        return HttpResponse.json(
          sellerResponse({ id: 7, startingBid: refetched === 1 ? 500 : 750 }),
        );
      }),
    );

    const { result } = renderHook(() => useListingDraft({}), {
      wrapper: makeWrapper(),
    });

    act(() => result.current.setParcel(sampleParcel));
    act(() => result.current.update("startingBid", 500));

    await act(async () => {
      await result.current.save();
    });
    expect(created).toBe(1);
    expect(result.current.state.auctionId).toBe(7);
    expect(result.current.state.dirty).toBe(false);

    act(() => result.current.update("startingBid", 750));

    await act(async () => {
      await result.current.save();
    });
    expect(created).toBe(1);
    expect(updated).toBe(1);
    expect(result.current.state.startingBid).toBe(750);
  });

  it("hydrates from an existing auction when an id is supplied", async () => {
    server.use(
      http.get("*/api/v1/auctions/99", () =>
        HttpResponse.json(
          sellerResponse({
            id: 99,
            startingBid: 2000,
            sellerDesc: "Lovely coast",
          }),
        ),
      ),
    );

    const { result } = renderHook(
      () => useListingDraft({ id: 99 }),
      { wrapper: makeWrapper() },
    );

    await waitFor(() =>
      expect(result.current.state.auctionId).toBe(99),
    );
    expect(result.current.state.startingBid).toBe(2000);
    expect(result.current.state.sellerDesc).toBe("Lovely coast");
    expect(result.current.state.parcel?.id).toBe(sampleParcel.id);
  });

  it("persists draft state to sessionStorage across remounts", async () => {
    const { result, unmount } = renderHook(() => useListingDraft({}), {
      wrapper: makeWrapper(),
    });
    act(() => result.current.setParcel(sampleParcel));
    act(() => result.current.update("startingBid", 1234));
    act(() => result.current.update("sellerDesc", "Preserve me"));
    unmount();

    const { result: result2 } = renderHook(() => useListingDraft({}), {
      wrapper: makeWrapper(),
    });

    await waitFor(() =>
      expect(result2.current.state.sellerDesc).toBe("Preserve me"),
    );
    expect(result2.current.state.startingBid).toBe(1234);
    expect(result2.current.state.parcel?.id).toBe(sampleParcel.id);
  });

  it("queues removed uploaded photos for DELETE on next save", async () => {
    const deleted: number[] = [];
    server.use(
      http.get("*/api/v1/auctions/11", () =>
        HttpResponse.json(
          sellerResponse({
            id: 11,
            photos: [
              {
                id: 101,
                url: "http://api.example/photos/101",
                contentType: "image/jpeg",
                sizeBytes: 1234,
                sortOrder: 0,
                uploadedAt: "2026-04-17T00:00:00Z",
              },
            ],
          }),
        ),
      ),
      http.put("*/api/v1/auctions/11", () =>
        HttpResponse.json(sellerResponse({ id: 11, photos: [] })),
      ),
      http.delete("*/api/v1/auctions/11/photos/:photoId", ({ params }) => {
        deleted.push(Number(params.photoId));
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const { result } = renderHook(
      () => useListingDraft({ id: 11 }),
      { wrapper: makeWrapper() },
    );

    await waitFor(() =>
      expect(result.current.state.uploadedPhotoIds).toEqual([101]),
    );

    act(() => result.current.removeUploadedPhoto(101));
    expect(result.current.state.removedPhotoIds).toEqual([101]);

    await act(async () => {
      await result.current.save();
    });

    expect(deleted).toEqual([101]);
    expect(result.current.state.removedPhotoIds).toEqual([]);
  });

  it("throws when save() is called without a parcel selected", async () => {
    const { result } = renderHook(() => useListingDraft({}), {
      wrapper: makeWrapper(),
    });

    await expect(
      act(async () => {
        await result.current.save();
      }),
    ).rejects.toThrow(/parcel/i);
  });
});
