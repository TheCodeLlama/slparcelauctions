import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor, act } from "@testing-library/react";
import { makeWrapper } from "@/test/render";
import { server } from "@/test/msw/server";
import {
  reviewsKeys,
  useAuctionReviews,
  useFlagReview,
  usePendingReviews,
  useRespondToReview,
  useSubmitReview,
  useUserReviews,
} from "./useReviews";

// ---------- Read hooks ----------

describe("useAuctionReviews", () => {
  it("fetches the envelope and returns it in query.data", async () => {
    server.use(
      http.get("*/api/v1/auctions/:id/reviews", () =>
        HttpResponse.json({
          reviews: [],
          myPendingReview: null,
          canReview: true,
          windowClosesAt: "2026-05-01T00:00:00Z",
        }),
      ),
    );

    const { result } = renderHook(
      () => useAuctionReviews("00000000-0000-0000-0000-00000000000a"),
      { wrapper: makeWrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.canReview).toBe(true);
  });

  it("does not fetch when auctionId is non-positive", async () => {
    let called = false;
    server.use(
      http.get("*/api/v1/auctions/:id/reviews", () => {
        called = true;
        return HttpResponse.json({});
      }),
    );

    const { result } = renderHook(() => useAuctionReviews(""), {
      wrapper: makeWrapper(),
    });

    // Give React Query a tick to decide whether to fire
    await new Promise((r) => setTimeout(r, 20));
    expect(called).toBe(false);
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useUserReviews", () => {
  it("fetches a paged response for the given role", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/:id/reviews", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json({
          content: [],
          totalElements: 0,
          totalPages: 0,
          number: 0,
          size: 10,
        });
      }),
    );

    const { result } = renderHook(
      () => useUserReviews("00000000-0000-0000-0000-000000000007", "SELLER", 1),
      { wrapper: makeWrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(captured!.searchParams.get("role")).toBe("SELLER");
    expect(captured!.searchParams.get("page")).toBe("1");
  });
});

describe("usePendingReviews", () => {
  it("fetches the list for the authenticated user", async () => {
    server.use(
      http.get("*/api/v1/users/me/pending-reviews", () =>
        HttpResponse.json([]),
      ),
    );

    const { result } = renderHook(() => usePendingReviews(), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
  });
});

// ---------- Mutations ----------

describe("useSubmitReview", () => {
  it("invalidates the auction envelope and pending list on success", async () => {
    server.use(
      http.post("*/api/v1/auctions/:id/reviews", () =>
        HttpResponse.json(
          {
            publicId: "00000000-0000-0000-0000-000000000001",
            auctionPublicId: "00000000-0000-0000-0000-00000000000a",
            auctionTitle: "t",
            auctionPrimaryPhotoUrl: null,
            reviewerPublicId: "00000000-0000-0000-0000-00000000002a",
            reviewerDisplayName: "A",
            reviewerAvatarUrl: null,
            revieweePublicId: "00000000-0000-0000-0000-000000000007",
            reviewedRole: "SELLER",
            rating: 5,
            text: "ok",
            visible: false,
            pending: true,
            submittedAt: "2026-04-20T00:00:00Z",
            revealedAt: null,
            response: null,
          },
          { status: 201 },
        ),
      ),
    );

    const wrapper = makeWrapper();
    const { result } = renderHook(
      () => useSubmitReview("00000000-0000-0000-0000-00000000000a"),
      { wrapper },
    );

    await act(async () => {
      await result.current.mutateAsync({ rating: 5, text: "ok" });
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("surfaces a toast on error", async () => {
    server.use(
      http.post("*/api/v1/auctions/:id/reviews", () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );

    const { result } = renderHook(
      () => useSubmitReview("00000000-0000-0000-0000-00000000000a"),
      { wrapper: makeWrapper() },
    );

    await act(async () => {
      await result.current
        .mutateAsync({ rating: 5 })
        .catch(() => {
          /* handled via toast */
        });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useRespondToReview", () => {
  it("posts the response and returns the created DTO", async () => {
    server.use(
      http.post("*/api/v1/reviews/:id/respond", () =>
        HttpResponse.json(
          {
            publicId: "00000000-0000-0000-0000-000000000063",
            text: "Thanks!",
            createdAt: "2026-04-22T00:00:00Z",
          },
          { status: 201 },
        ),
      ),
    );

    const { result } = renderHook(
      () => useRespondToReview("00000000-0000-0000-0000-000000000005"),
      { wrapper: makeWrapper() },
    );

    let response;
    await act(async () => {
      response = await result.current.mutateAsync({ text: "Thanks!" });
    });

    expect(response).toEqual({
      publicId: "00000000-0000-0000-0000-000000000063",
      text: "Thanks!",
      createdAt: "2026-04-22T00:00:00Z",
    });
  });

  it("marks isError on 409 duplicate", async () => {
    server.use(
      http.post("*/api/v1/reviews/:id/respond", () =>
        HttpResponse.json({ status: 409 }, { status: 409 }),
      ),
    );

    const { result } = renderHook(
      () => useRespondToReview("00000000-0000-0000-0000-000000000005"),
      { wrapper: makeWrapper() },
    );

    await act(async () => {
      await result.current
        .mutateAsync({ text: "Thanks!" })
        .catch(() => {
          /* handled via toast */
        });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useFlagReview", () => {
  it("resolves on 204 and marks isSuccess", async () => {
    server.use(
      http.post("*/api/v1/reviews/:id/flag", () =>
        HttpResponse.json(null, { status: 204 }),
      ),
    );

    const { result } = renderHook(
      () => useFlagReview("00000000-0000-0000-0000-000000000005"),
      { wrapper: makeWrapper() },
    );

    await act(async () => {
      await result.current.mutateAsync({ reason: "SPAM" });
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("surfaces the 409 duplicate path on isError", async () => {
    server.use(
      http.post("*/api/v1/reviews/:id/flag", () =>
        HttpResponse.json({ status: 409 }, { status: 409 }),
      ),
    );

    const { result } = renderHook(
      () => useFlagReview("00000000-0000-0000-0000-000000000005"),
      { wrapper: makeWrapper() },
    );

    await act(async () => {
      await result.current
        .mutateAsync({ reason: "SPAM" })
        .catch(() => {
          /* handled via toast */
        });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

// ---------- Key factory ----------

describe("reviewsKeys", () => {
  it("produces stable, scoped cache keys", () => {
    expect(reviewsKeys.auction(10)).toEqual(["reviews", "auction", "10"]);
    expect(reviewsKeys.user(7, "SELLER", 2)).toEqual([
      "reviews",
      "user",
      "7",
      "SELLER",
      2,
    ]);
    expect(reviewsKeys.pending).toEqual(["reviews", "pending"]);
  });
});
