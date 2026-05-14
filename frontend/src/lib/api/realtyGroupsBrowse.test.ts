import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { getBrowseGroups } from "./realtyGroupsBrowse";
import type { Page } from "@/types/page";
import type { BrowseGroupCard } from "./realtyGroupsBrowse";

function emptyPage(): Page<BrowseGroupCard> {
  return {
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size: 20,
  };
}

describe("getBrowseGroups", () => {
  it("hits GET /api/v1/realty-groups with every supplied param", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(emptyPage());
      }),
    );

    await getBrowseGroups({
      q: "mainland",
      page: 2,
      size: 30,
      sort: "MOST_ACTIVE_LISTINGS",
    });

    const url = new URL(capturedUrl);
    expect(url.pathname).toBe("/api/v1/realty-groups");
    expect(url.searchParams.get("q")).toBe("mainland");
    expect(url.searchParams.get("page")).toBe("2");
    expect(url.searchParams.get("size")).toBe("30");
    expect(url.searchParams.get("sort")).toBe("MOST_ACTIVE_LISTINGS");
  });

  it("omits the q param when q is undefined", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(emptyPage());
      }),
    );

    await getBrowseGroups({ page: 0, size: 20, sort: "RATING" });

    const url = new URL(capturedUrl);
    expect(url.searchParams.has("q")).toBe(false);
    expect(url.searchParams.get("page")).toBe("0");
    expect(url.searchParams.get("size")).toBe("20");
    expect(url.searchParams.get("sort")).toBe("RATING");
  });

  it("omits the q param when q is blank / whitespace", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(emptyPage());
      }),
    );

    await getBrowseGroups({ q: "   ", page: 0, size: 20, sort: "RATING" });

    const url = new URL(capturedUrl);
    expect(url.searchParams.has("q")).toBe(false);
  });

  it("trims q before sending it on the wire", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(emptyPage());
      }),
    );

    await getBrowseGroups({ q: "  mainland  ", page: 0, size: 20, sort: "RATING" });

    const url = new URL(capturedUrl);
    expect(url.searchParams.get("q")).toBe("mainland");
  });

  it("applies the documented defaults when page / size / sort are absent", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(emptyPage());
      }),
    );

    await getBrowseGroups({});

    const url = new URL(capturedUrl);
    expect(url.searchParams.get("page")).toBe("0");
    expect(url.searchParams.get("size")).toBe("20");
    expect(url.searchParams.get("sort")).toBe("RATING");
  });

  it("returns the Spring Page envelope verbatim", async () => {
    const card: BrowseGroupCard = {
      publicId: "00000000-0000-0000-0000-000000000001",
      name: "Mainland Realty",
      slug: "mainland-realty",
      tagline: "Premium Mainland brokerage.",
      logoUrl: null,
      coverUrl: null,
      foundedAt: "2026-01-01T00:00:00Z",
      memberCount: 1,
      memberSeatLimit: 8,
      activeListingsCount: 0,
      completedSalesCount: 0,
      rating: { averageRating: null, reviewCount: 0 },
    };
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json({
          content: [card],
          totalElements: 1,
          totalPages: 1,
          number: 0,
          size: 20,
        }),
      ),
    );

    const result = await getBrowseGroups({});
    expect(result.content).toHaveLength(1);
    expect(result.content[0]?.slug).toBe("mainland-realty");
    expect(result.totalElements).toBe(1);
  });
});
