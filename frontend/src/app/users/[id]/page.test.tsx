import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { generateMetadata } from "./page";

// next/navigation is mocked globally — nothing to override here.

describe("PublicProfilePage generateMetadata", () => {
  beforeEach(() => {
    // Nothing stateful to reset for the metadata path.
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("returns a generic title for a non-numeric id", async () => {
    const meta = await generateMetadata({
      params: Promise.resolve({ id: "nope" }),
    });
    expect(meta.title).toBe("Profile · SLPA");
  });

  it("returns a generic title for a non-positive id", async () => {
    const meta = await generateMetadata({
      params: Promise.resolve({ id: "0" }),
    });
    expect(meta.title).toBe("Profile · SLPA");
  });

  it("emits displayName and bio-derived description", async () => {
    server.use(
      http.get("*/api/v1/users/:id", () =>
        HttpResponse.json({
          id: 42,
          displayName: "Carol Seller",
          bio: "Lifelong landscape architect offering beachfront parcels across the grid.",
          profilePicUrl: "https://cdn.example/avatar.jpg",
          slAvatarUuid: null,
          slAvatarName: null,
          slUsername: null,
          slDisplayName: null,
          verified: true,
          avgSellerRating: 4.5,
          avgBuyerRating: null,
          totalSellerReviews: 9,
          totalBuyerReviews: 0,
          completedSales: 12,
          createdAt: "2025-11-03T00:00:00Z",
        }),
      ),
    );

    const meta = await generateMetadata({
      params: Promise.resolve({ id: "42" }),
    });
    expect(meta.title).toBe("Carol Seller · SLPA");
    expect(meta.description).toContain("Lifelong landscape architect");
    expect(meta.openGraph?.images).toEqual(["https://cdn.example/avatar.jpg"]);
    expect((meta.twitter as { card: string } | undefined)?.card).toBe(
      "summary_large_image",
    );
    expect(meta.robots).toEqual({ index: true, follow: true });
  });

  it("falls back to the default description when bio is null", async () => {
    server.use(
      http.get("*/api/v1/users/:id", () =>
        HttpResponse.json({
          id: 42,
          displayName: "Carol Seller",
          bio: null,
          profilePicUrl: null,
          slAvatarUuid: null,
          slAvatarName: null,
          slUsername: null,
          slDisplayName: null,
          verified: true,
          avgSellerRating: null,
          avgBuyerRating: null,
          totalSellerReviews: 0,
          totalBuyerReviews: 0,
          completedSales: 0,
          createdAt: "2025-11-03T00:00:00Z",
        }),
      ),
    );

    const meta = await generateMetadata({
      params: Promise.resolve({ id: "42" }),
    });
    expect(meta.description).toBe("Second Life parcel seller on SLPA.");
    expect((meta.twitter as { card: string } | undefined)?.card).toBe("summary");
    expect(meta.openGraph?.images).toEqual([]);
  });

  it("truncates long bios to 200 chars with an ellipsis", async () => {
    const longBio =
      "I have been building communities across Second Life since 2006 and specialise in lakeside parcels with customised landscaping. " +
      "My clients include many of the grid's largest role-playing groups and regional event organisers.";
    server.use(
      http.get("*/api/v1/users/:id", () =>
        HttpResponse.json({
          id: 42,
          displayName: "Carol Seller",
          bio: longBio,
          profilePicUrl: null,
          slAvatarUuid: null,
          slAvatarName: null,
          slUsername: null,
          slDisplayName: null,
          verified: true,
          avgSellerRating: null,
          avgBuyerRating: null,
          totalSellerReviews: 0,
          totalBuyerReviews: 0,
          completedSales: 0,
          createdAt: "2025-11-03T00:00:00Z",
        }),
      ),
    );

    const meta = await generateMetadata({
      params: Promise.resolve({ id: "42" }),
    });
    expect(meta.description?.length ?? 0).toBeLessThanOrEqual(200);
    expect(meta.description?.endsWith("…")).toBe(true);
  });

  it("returns the generic title when the backend 404s", async () => {
    server.use(
      http.get("*/api/v1/users/:id", () =>
        HttpResponse.json(
          { status: 404, title: "Not Found" },
          { status: 404 },
        ),
      ),
    );
    const meta = await generateMetadata({
      params: Promise.resolve({ id: "9999" }),
    });
    expect(meta.title).toBe("Profile · SLPA");
  });
});
