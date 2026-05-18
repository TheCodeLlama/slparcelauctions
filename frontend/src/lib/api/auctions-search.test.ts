import { describe, it, expect } from "vitest";
import { server } from "@/test/msw/server";
import { http, HttpResponse } from "msw";
import {
  searchAuctions,
  fetchFeatured,
  resolveBrowseInitialData,
} from "./auctions-search";
import { isApiError } from "@/lib/api";

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

  it("fetchFeatured hits the right rail endpoint", async () => {
    let seen = "";
    server.use(
      http.get("*/api/v1/auctions/rails/:category", ({ params }) => {
        seen = String(params.category);
        return HttpResponse.json({ content: [] });
      }),
    );
    await fetchFeatured("featured");
    expect(seen).toBe("featured");
  });
});

describe("resolveBrowseInitialData", () => {
  const okPayload = {
    content: [],
    page: 0,
    size: 24,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true,
  };

  it("passes a successful response straight through with no errorCode", async () => {
    server.use(
      http.get("*/api/v1/auctions/search", () =>
        HttpResponse.json({ ...okPayload, totalElements: 3 }),
      ),
    );
    const r = await resolveBrowseInitialData({ sort: "newest" });
    expect(r.data.totalElements).toBe(3);
    expect(r.errorCode).toBeUndefined();
  });

  it("swallows a 4xx REGION_NOT_FOUND into empty results + the code", async () => {
    server.use(
      http.get(
        "*/api/v1/auctions/search",
        () =>
          new HttpResponse(
            JSON.stringify({
              status: 400,
              title: "Region Not Found",
              detail: "Region not found in Grid Survey: R",
              code: "REGION_NOT_FOUND",
              field: "near_region",
              regionName: "R",
            }),
            {
              status: 400,
              headers: { "Content-Type": "application/problem+json" },
            },
          ),
      ),
    );
    const r = await resolveBrowseInitialData({ nearRegion: "R" });
    expect(r.errorCode).toBe("REGION_NOT_FOUND");
    expect(r.data.content).toEqual([]);
    expect(r.data.totalElements).toBe(0);
    expect(r.data.first).toBe(true);
  });

  it("swallows a generic 400 with no code field (errorCode undefined, still empty)", async () => {
    server.use(
      http.get(
        "*/api/v1/auctions/search",
        () =>
          new HttpResponse(
            JSON.stringify({ status: 400, title: "Bad Request" }),
            {
              status: 400,
              headers: { "Content-Type": "application/problem+json" },
            },
          ),
      ),
    );
    const r = await resolveBrowseInitialData({ region: "x" });
    expect(r.errorCode).toBeUndefined();
    expect(r.data.content).toEqual([]);
  });

  it("rethrows a 5xx so the route error boundary can handle it", async () => {
    server.use(
      http.get(
        "*/api/v1/auctions/search",
        () =>
          new HttpResponse(
            JSON.stringify({ status: 500, title: "Internal Server Error" }),
            {
              status: 500,
              headers: { "Content-Type": "application/problem+json" },
            },
          ),
      ),
    );
    let caught: unknown;
    try {
      await resolveBrowseInitialData({});
    } catch (e) {
      caught = e;
    }
    expect(isApiError(caught)).toBe(true);
    expect((caught as { status: number }).status).toBe(500);
  });

  it("rethrows a non-ApiError (network failure) untouched", async () => {
    server.use(
      http.get("*/api/v1/auctions/search", () => HttpResponse.error()),
    );
    await expect(resolveBrowseInitialData({})).rejects.toBeDefined();
  });
});
