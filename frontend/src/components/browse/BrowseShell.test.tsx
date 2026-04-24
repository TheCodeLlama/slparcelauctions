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
});
