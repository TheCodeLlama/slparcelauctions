import { describe, it, expect } from "vitest";
import { server } from "@/test/msw/server";
import { http, HttpResponse } from "msw";
import { searchAuctions, fetchFeatured } from "./auctions-search";

describe("auctions-search API", () => {
  it("search returns the paged payload", async () => {
    server.use(
      http.get("*/api/v1/auctions/search", () =>
        HttpResponse.json({
          content: [],
          page: 0,
          size: 24,
          totalElements: 0,
          totalPages: 0,
          first: true,
          last: true,
        }),
      ),
    );
    const r = await searchAuctions({ sort: "newest", page: 0, size: 24 });
    expect(r.content).toEqual([]);
    expect(r.first).toBe(true);
  });

  it("forwards filter params on the URL", async () => {
    let seen = "";
    server.use(
      http.get("*/api/v1/auctions/search", ({ request }) => {
        seen = new URL(request.url).search;
        return HttpResponse.json({
          content: [],
          page: 0,
          size: 24,
          totalElements: 0,
          totalPages: 0,
          first: true,
          last: true,
        });
      }),
    );
    await searchAuctions({ region: "Tula", sort: "ending_soonest" });
    expect(seen).toContain("region=Tula");
    expect(seen).toContain("sort=ending_soonest");
  });

  it("throws ApiError on 429", async () => {
    server.use(
      http.get(
        "*/api/v1/auctions/search",
        () =>
          new HttpResponse(
            JSON.stringify({
              code: "TOO_MANY_REQUESTS",
              message: "Rate limited",
              status: 429,
            }),
            {
              status: 429,
              headers: {
                "Retry-After": "42",
                "Content-Type": "application/problem+json",
              },
            },
          ),
      ),
    );
    await expect(searchAuctions({})).rejects.toMatchObject({ status: 429 });
  });

  it("fetchFeatured hits the right endpoint", async () => {
    let seen = "";
    server.use(
      http.get("*/api/v1/auctions/featured/:category", ({ params }) => {
        seen = String(params.category);
        return HttpResponse.json({ content: [] });
      }),
    );
    await fetchFeatured("ending-soon");
    expect(seen).toBe("ending-soon");
  });
});
