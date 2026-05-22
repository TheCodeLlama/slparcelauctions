import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import {
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query";
import type { ReactNode } from "react";
import { server } from "@/test/msw/server";
import { useBrowseGroups } from "./useBrowseGroups";
import type { Page } from "@/types/page";
import type { BrowseGroupCard } from "@/lib/api/realtyGroupsBrowse";

function emptyPage(): Page<BrowseGroupCard> {
  return {
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size: 20,
  };
}

function pageOf(cards: BrowseGroupCard[]): Page<BrowseGroupCard> {
  return {
    content: cards,
    totalElements: cards.length,
    totalPages: Math.max(1, Math.ceil(cards.length / 20)),
    number: 0,
    size: 20,
  };
}

function makeCard(overrides: Partial<BrowseGroupCard> = {}): BrowseGroupCard {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    tagline: "Premium Mainland brokerage.",
    logoLightUrl: null, logoDarkUrl: null,
    coverLightUrl: null, coverDarkUrl: null,
    foundedAt: "2026-01-01T00:00:00Z",
    memberCount: 1,
    memberSeatLimit: 8,
    activeListingsCount: 0,
    completedSalesCount: 0,
    rating: { averageRating: null, reviewCount: 0 },
    ...overrides,
  };
}

function newQc() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
}

function makeWrapper(qc: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  };
}

describe("useBrowseGroups", () => {
  it("hits /api/v1/realty-groups with the supplied query params", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(emptyPage());
      }),
    );

    const qc = newQc();
    const { result } = renderHook(
      () =>
        useBrowseGroups({
          q: "mainland",
          page: 2,
          size: 30,
          sort: "MOST_ACTIVE_LISTINGS",
        }),
      { wrapper: makeWrapper(qc) },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const url = new URL(capturedUrl);
    expect(url.pathname).toBe("/api/v1/realty-groups");
    expect(url.searchParams.get("q")).toBe("mainland");
    expect(url.searchParams.get("page")).toBe("2");
    expect(url.searchParams.get("size")).toBe("30");
    expect(url.searchParams.get("sort")).toBe("MOST_ACTIVE_LISTINGS");
  });

  it("omits the q param when q is empty / undefined", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(emptyPage());
      }),
    );

    const qc = newQc();
    const { result } = renderHook(
      () => useBrowseGroups({ page: 0, size: 20, sort: "RATING" }),
      { wrapper: makeWrapper(qc) },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const url = new URL(capturedUrl);
    expect(url.searchParams.has("q")).toBe(false);
    expect(url.searchParams.get("sort")).toBe("RATING");
  });

  it("caches under [\"realty-groups\", \"browse\", {q, page, size, sort}]", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(pageOf([makeCard()])),
      ),
    );
    const qc = newQc();
    const { result } = renderHook(
      () =>
        useBrowseGroups({
          q: "mainland",
          page: 0,
          size: 20,
          sort: "RATING",
        }),
      { wrapper: makeWrapper(qc) },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const cached = qc.getQueryData([
      "realty-groups",
      "browse",
      {
        q: "mainland",
        page: 0,
        size: 20,
        sort: "RATING",
        direction: "DESC",
        minRating: 0,
        minReviews: 0,
        activeOnly: false,
      },
    ]);
    expect(cached).toBeDefined();
  });

  it("keeps prior data visible while a new page is fetching (placeholderData=keepPreviousData)", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        const page = new URL(request.url).searchParams.get("page");
        if (page === "0") {
          return HttpResponse.json(pageOf([makeCard({ name: "Alpha", slug: "alpha" })]));
        }
        // Hold the second page so we can observe placeholder behavior.
        return new Promise<Response>(() => {
          /* never resolve */
        });
      }),
    );

    const qc = newQc();
    let params = { q: "", page: 0, size: 20, sort: "RATING" as const };
    const { result, rerender } = renderHook(() => useBrowseGroups(params), {
      wrapper: makeWrapper(qc),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.content[0]?.name).toBe("Alpha");

    params = { ...params, page: 1 };
    rerender();

    // While page 1 is still pending the hook should expose the prior data
    // rather than dropping back to undefined.
    expect(result.current.data?.content[0]?.name).toBe("Alpha");
    expect(result.current.isPlaceholderData).toBe(true);
  });
});
