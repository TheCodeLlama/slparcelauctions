import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import type { Page } from "@/types/page";
import type {
  AuctionReviewsResponse,
  PendingReviewDto,
  ReviewDto,
  ReviewResponseDto,
} from "@/types/review";
import {
  flagReview,
  getAuctionReviews,
  getPendingReviews,
  getUserReviews,
  respondToReview,
  submitReview,
} from "./reviews";

// ---------- Fixture builders ----------

function reviewDto(overrides: Partial<ReviewDto> = {}): ReviewDto {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    auctionPublicId: "00000000-0000-0000-0000-00000000000a",
    auctionTitle: "Aurora Parcel",
    auctionPrimaryPhotoUrl: "/api/v1/auctions/10/photos/1/bytes",
    reviewerPublicId: "00000000-0000-0000-0000-00000000002a",
    reviewerDisplayName: "Alice",
    reviewerAvatarUrl: "/api/v1/users/42/avatar/256",
    revieweePublicId: "00000000-0000-0000-0000-000000000007",
    reviewedRole: "SELLER",
    rating: 5,
    text: "Great seller",
    visible: true,
    pending: false,
    submittedAt: "2026-04-20T12:00:00Z",
    revealedAt: "2026-04-21T12:00:00Z",
    response: null,
    ...overrides,
  };
}

function pendingReviewDto(
  overrides: Partial<PendingReviewDto> = {},
): PendingReviewDto {
  return {
    auctionPublicId: "00000000-0000-0000-0000-00000000000a",
    title: "Aurora Parcel",
    primaryPhotoUrl: null,
    counterpartyPublicId: "00000000-0000-0000-0000-000000000007",
    counterpartyDisplayName: "Bob",
    counterpartyAvatarUrl: null,
    escrowCompletedAt: "2026-04-10T00:00:00Z",
    windowClosesAt: "2026-04-24T00:00:00Z",
    hoursRemaining: 72,
    viewerRole: "BUYER",
    ...overrides,
  };
}

function pageOf<T>(content: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 10,
    ...overrides,
  };
}

// ---------- getAuctionReviews ----------

describe("getAuctionReviews", () => {
  it("GETs /api/v1/auctions/{id}/reviews and returns the envelope", async () => {
    let captured: URL | null = null;
    const envelope: AuctionReviewsResponse = {
      reviews: [reviewDto()],
      myPendingReview: null,
      canReview: false,
      windowClosesAt: null,
    };
    server.use(
      http.get("*/api/v1/auctions/:id/reviews", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(envelope);
      }),
    );

    const result = await getAuctionReviews(10);

    expect(captured).not.toBeNull();
    expect(captured!.pathname).toBe("/api/v1/auctions/10/reviews");
    expect(result.reviews).toHaveLength(1);
    expect(result.canReview).toBe(false);
  });
});

// ---------- submitReview ----------

describe("submitReview", () => {
  it("POSTs { rating, text } and returns the created ReviewDto", async () => {
    let capturedBody: unknown = null;
    let capturedMethod = "";
    server.use(
      http.post("*/api/v1/auctions/:id/reviews", async ({ request }) => {
        capturedMethod = request.method;
        capturedBody = await request.json();
        return HttpResponse.json(reviewDto({ visible: false, pending: true }), {
          status: 201,
        });
      }),
    );

    const result = await submitReview(10, { rating: 5, text: "ok" });

    expect(capturedMethod).toBe("POST");
    expect(capturedBody).toEqual({ rating: 5, text: "ok" });
    expect(result.pending).toBe(true);
  });
});

// ---------- getUserReviews ----------

describe("getUserReviews", () => {
  it("sends role/page/size by default (page=0, size=10)", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/:id/reviews", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(pageOf([reviewDto()]));
      }),
    );

    const result = await getUserReviews(7, "SELLER");

    expect(captured!.pathname).toBe("/api/v1/users/7/reviews");
    expect(captured!.searchParams.get("role")).toBe("SELLER");
    expect(captured!.searchParams.get("page")).toBe("0");
    expect(captured!.searchParams.get("size")).toBe("10");
    expect(result.content).toHaveLength(1);
  });

  it("honors explicit page and size overrides", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/:id/reviews", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(pageOf([], { number: 2, size: 5 }));
      }),
    );

    await getUserReviews(7, "BUYER", { page: 2, size: 5 });

    expect(captured!.searchParams.get("role")).toBe("BUYER");
    expect(captured!.searchParams.get("page")).toBe("2");
    expect(captured!.searchParams.get("size")).toBe("5");
  });
});

// ---------- getPendingReviews ----------

describe("getPendingReviews", () => {
  it("GETs /api/v1/users/me/pending-reviews and returns the list", async () => {
    server.use(
      http.get("*/api/v1/users/me/pending-reviews", () =>
        HttpResponse.json([pendingReviewDto()]),
      ),
    );

    const result = await getPendingReviews();

    expect(result).toHaveLength(1);
    expect(result[0].auctionPublicId).toBe("00000000-0000-0000-0000-00000000000a");
  });
});

// ---------- respondToReview ----------

describe("respondToReview", () => {
  it("POSTs { text } and returns the created ReviewResponseDto", async () => {
    let capturedBody: unknown = null;
    const response: ReviewResponseDto = {
      publicId: "00000000-0000-0000-0000-000000000063",
      text: "Thanks!",
      createdAt: "2026-04-22T00:00:00Z",
    };
    server.use(
      http.post("*/api/v1/reviews/:id/respond", async ({ request }) => {
        capturedBody = await request.json();
        return HttpResponse.json(response, { status: 201 });
      }),
    );

    const result = await respondToReview(5, { text: "Thanks!" });

    expect(capturedBody).toEqual({ text: "Thanks!" });
    expect(result.publicId).toBe("00000000-0000-0000-0000-000000000063");
  });
});

// ---------- flagReview ----------

describe("flagReview", () => {
  it("POSTs { reason, elaboration } and resolves to void on 204", async () => {
    let capturedBody: unknown = null;
    server.use(
      http.post("*/api/v1/reviews/:id/flag", async ({ request }) => {
        capturedBody = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await flagReview(5, { reason: "SPAM" });

    expect(capturedBody).toEqual({ reason: "SPAM" });
  });

  it("passes elaboration through for OTHER reasons", async () => {
    let capturedBody: unknown = null;
    server.use(
      http.post("*/api/v1/reviews/:id/flag", async ({ request }) => {
        capturedBody = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await flagReview(5, { reason: "OTHER", elaboration: "Explains it" });

    expect(capturedBody).toEqual({
      reason: "OTHER",
      elaboration: "Explains it",
    });
  });
});
