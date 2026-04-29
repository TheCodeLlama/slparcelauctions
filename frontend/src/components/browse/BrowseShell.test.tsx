import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { BrowseShell } from "./BrowseShell";
import { defaultAuctionSearchQuery } from "@/lib/search/url-codec";
import type { SearchResponse } from "@/types/search";

// Override the global next/navigation mock locally so the test can pin
// `router.replace` and `useSearchParams` across re-renders.
const { replaceMock, searchParamsRef } = vi.hoisted(() => ({
  replaceMock: vi.fn(),
  searchParamsRef: { current: new URLSearchParams() },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: vi.fn(),
    replace: replaceMock,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/browse",
  useSearchParams: () => searchParamsRef.current,
}));

const emptyResponse: SearchResponse = {
  content: [],
  page: 0,
  size: 24,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

// TagSelector fetches /api/v1/parcel-tags — handle it so MSW doesn't
// complain about unhandled requests when the desktop sidebar mounts.
function stubEmptyTags() {
  server.use(http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])));
}

describe("BrowseShell", () => {
  beforeEach(() => {
    replaceMock.mockClear();
    searchParamsRef.current = new URLSearchParams();
    if (typeof sessionStorage !== "undefined") sessionStorage.clear();
  });

  it("seeds from initialData without firing a network fetch on first render", async () => {
    stubEmptyTags();
    let fetches = 0;
    server.use(
      http.get("*/api/v1/auctions/search", () => {
        fetches++;
        return HttpResponse.json(emptyResponse);
      }),
    );
    renderWithProviders(
      <BrowseShell
        initialQuery={defaultAuctionSearchQuery}
        initialData={emptyResponse}
      />,
    );
    // Give React a tick to flush effects. initialData means React Query
    // considers the cache fresh and should not fire a network request.
    await waitFor(() => {
      expect(screen.getByText("Browse")).toBeInTheDocument();
    });
    expect(fetches).toBe(0);
  });

  it("pushes a new URL via router.replace on sort change (desktop immediate)", async () => {
    stubEmptyTags();
    server.use(
      http.get("*/api/v1/auctions/search", () =>
        HttpResponse.json(emptyResponse),
      ),
    );
    renderWithProviders(
      <BrowseShell
        initialQuery={defaultAuctionSearchQuery}
        initialData={emptyResponse}
      />,
    );
    await userEvent.selectOptions(
      screen.getByLabelText(/sort/i),
      "ending_soonest",
    );
    expect(replaceMock).toHaveBeenCalled();
    const calledWith = replaceMock.mock.calls[0][0] as string;
    expect(calledWith).toContain("sort=ending_soonest");
  });

  it("writes the current URL to sessionStorage on mount", async () => {
    stubEmptyTags();
    server.use(
      http.get("*/api/v1/auctions/search", () =>
        HttpResponse.json(emptyResponse),
      ),
    );
    searchParamsRef.current = new URLSearchParams("region=Tula");
    renderWithProviders(
      <BrowseShell
        initialQuery={{ ...defaultAuctionSearchQuery, region: "Tula" }}
        initialData={emptyResponse}
      />,
    );
    await waitFor(() => {
      expect(sessionStorage.getItem("last-browse-url")).toMatch(/region=Tula/);
    });
  });

  it("resets sort=nearest to the default when near_region is cleared", async () => {
    // Regression: commitRegion(undefined) drops nearRegion + distance
    // but leaves sort=nearest in place → backend 400
    // NEAREST_REQUIRES_NEAR_REGION on next fetch. BrowseShell.applyQuery
    // now normalizes sort=nearest to the default when nearRegion is
    // absent.
    stubEmptyTags();
    server.use(
      http.get("*/api/v1/auctions/search", () =>
        HttpResponse.json(emptyResponse),
      ),
    );
    searchParamsRef.current = new URLSearchParams(
      "near_region=Tula&sort=nearest",
    );
    renderWithProviders(
      <BrowseShell
        initialQuery={{
          ...defaultAuctionSearchQuery,
          nearRegion: "Tula",
          sort: "nearest",
        }}
        initialData={emptyResponse}
      />,
    );
    // Clear the region via the Clear-all button, which routes through
    // applyQuery with { ...defaultAuctionSearchQuery } (no nearRegion).
    // An alternative path — typing blank in the region input — exercises
    // the same code path in BrowseShell.
    const removeButtons = await screen.findAllByRole("button", {
      name: /remove filter/i,
    });
    await userEvent.click(removeButtons[0]);
    expect(replaceMock).toHaveBeenCalled();
    const url = replaceMock.mock.calls[0][0] as string;
    // sort=nearest must have been dropped (or switched to default) when
    // the region was cleared; the default is "newest" which is stripped
    // on encode, so the URL should not contain sort=nearest.
    expect(url).not.toContain("sort=nearest");
    expect(url).not.toContain("near_region=Tula");
  });

  it("preserves sellerId across sort changes when fixedFilters is set", async () => {
    stubEmptyTags();
    server.use(
      http.get("*/api/v1/auctions/search", () =>
        HttpResponse.json(emptyResponse),
      ),
    );
    renderWithProviders(
      <BrowseShell
        initialQuery={{ ...defaultAuctionSearchQuery, sellerId: 42 }}
        initialData={emptyResponse}
        fixedFilters={{ sellerId: 42 }}
        hiddenFilterGroups={["distance"]}
      />,
    );
    await userEvent.selectOptions(
      screen.getByLabelText(/sort/i),
      "lowest_price",
    );
    expect(replaceMock).toHaveBeenCalled();
    const url = replaceMock.mock.calls[0][0] as string;
    expect(url).toContain("seller_id=42");
    expect(url).toContain("sort=lowest_price");
  });

  it("renders in dark mode", async () => {
    stubEmptyTags();
    server.use(
      http.get("*/api/v1/auctions/search", () =>
        HttpResponse.json(emptyResponse),
      ),
    );
    renderWithProviders(
      <BrowseShell
        initialQuery={defaultAuctionSearchQuery}
        initialData={emptyResponse}
      />,
      { theme: "dark", forceTheme: true },
    );
    await waitFor(() => {
      expect(screen.getByText("Browse")).toBeInTheDocument();
    });
  });
});
