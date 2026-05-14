import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { fireEvent } from "@testing-library/react";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { GroupsBrowseClient } from "./GroupsBrowseClient";
import type { Page } from "@/types/page";
import type { BrowseGroupCard } from "@/lib/api/realtyGroupsBrowse";

/**
 * `vitest.setup.ts` installs a global mock of `next/navigation`. We override
 * it here so the test can capture `router.push` / `router.replace` calls and
 * drive `useSearchParams` from a per-test state holder. Without this override
 * the global stub returns a brand-new `vi.fn()` on every render, making the
 * spies unobservable from outside the component tree.
 */
const pushSpy = vi.fn();
const replaceSpy = vi.fn();

const searchParamsState: { value: URLSearchParams } = {
  value: new URLSearchParams(),
};

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: pushSpy,
    replace: replaceSpy,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: () => searchParamsState.value,
  usePathname: () => "/groups",
}));

function setSearchParams(qs: string) {
  searchParamsState.value = new URLSearchParams(qs);
}

function makeCard(
  overrides: Partial<BrowseGroupCard> = {},
): BrowseGroupCard {
  return {
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
    ...overrides,
  };
}

function asPage(content: BrowseGroupCard[]): Page<BrowseGroupCard> {
  return {
    content,
    totalElements: content.length,
    totalPages: Math.max(1, Math.ceil(content.length / 20)),
    number: 0,
    size: 20,
  };
}

describe("GroupsBrowseClient", () => {
  beforeEach(() => {
    pushSpy.mockReset();
    replaceSpy.mockReset();
    setSearchParams("");
  });

  // Guard against fake-timer leaks across tests: if a previous test enabled
  // fake timers and timed out before the finally block fired, subsequent
  // tests would hang because `waitFor` polls via `setTimeout`. Unconditionally
  // restore real timers here.
  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders cards from the MSW-handled response", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(
          asPage([
            makeCard({
              name: "Alpha",
              slug: "alpha",
              publicId: "00000000-0000-0000-0000-00000000aaaa",
            }),
            makeCard({
              name: "Beta",
              slug: "beta",
              publicId: "00000000-0000-0000-0000-00000000bbbb",
            }),
          ]),
        ),
      ),
    );

    renderWithProviders(<GroupsBrowseClient />);

    await waitFor(() => {
      expect(screen.getByText("Alpha")).toBeInTheDocument();
      expect(screen.getByText("Beta")).toBeInTheDocument();
    });
  });

  it("typing in the search input fires a debounced URL update with ?q=...", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(asPage([])),
      ),
    );

    renderWithProviders(<GroupsBrowseClient />);

    await waitFor(() =>
      expect(
        screen.getByPlaceholderText(/search groups/i),
      ).toBeInTheDocument(),
    );

    fireEvent.change(screen.getByPlaceholderText(/search groups/i), {
      target: { value: "mainland" },
    });

    // Pre-debounce (<300ms): the URL has not been written yet. The change
    // is buffered in local input state but not yet pushed into the router.
    expect(replaceSpy).not.toHaveBeenCalled();

    // Post-debounce: the URL write fires exactly once, via `router.replace`,
    // with the new `?q=` param. `waitFor` polls until the debounce flushes.
    await waitFor(
      () => {
        expect(replaceSpy).toHaveBeenCalledWith("/groups?q=mainland");
      },
      { timeout: 2000 },
    );
    expect(replaceSpy).toHaveBeenCalledTimes(1);
    expect(pushSpy).not.toHaveBeenCalled();
  });

  it("selecting a different sort fires a URL update with ?sort=...", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(asPage([])),
      ),
    );

    renderWithProviders(<GroupsBrowseClient />);

    await waitFor(() =>
      expect(
        screen.getByRole("combobox", { name: /sort groups/i }),
      ).toBeInTheDocument(),
    );

    fireEvent.change(screen.getByRole("combobox", { name: /sort groups/i }), {
      target: { value: "NEWEST" },
    });

    expect(pushSpy).toHaveBeenCalledWith("/groups?sort=NEWEST");
  });

  it("clicking a card pushes /groups/{slug}", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(
          asPage([makeCard({ name: "Alpha", slug: "alpha-realty" })]),
        ),
      ),
    );

    renderWithProviders(<GroupsBrowseClient />);

    await waitFor(() => expect(screen.getByText("Alpha")).toBeInTheDocument());
    fireEvent.click(screen.getByText("Alpha"));

    expect(pushSpy).toHaveBeenCalledWith("/groups/alpha-realty");
  });

  it("clicking 'Start a group' pushes /groups/new", async () => {
    server.use(
      http.get("*/api/v1/realty-groups", () =>
        HttpResponse.json(asPage([])),
      ),
    );

    renderWithProviders(<GroupsBrowseClient />);

    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: /start a group/i }),
      ).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole("button", { name: /start a group/i }));

    expect(pushSpy).toHaveBeenCalledWith("/groups/new");
  });

  it("renders a loading skeleton while the request is pending", async () => {
    let resolve!: (value: unknown) => void;
    const pending = new Promise((r) => {
      resolve = r;
    });
    server.use(
      http.get("*/api/v1/realty-groups", async () => {
        await pending;
        return HttpResponse.json(asPage([]));
      }),
    );

    renderWithProviders(<GroupsBrowseClient />);

    expect(screen.getByLabelText(/loading groups/i)).toBeInTheDocument();

    resolve(undefined);
    await waitFor(() =>
      expect(
        screen.queryByLabelText(/loading groups/i),
      ).not.toBeInTheDocument(),
    );
  });

  it("seeds the input from the ?q= URL param and reflects it back to the request", async () => {
    setSearchParams("q=mainland&sort=NEWEST");

    let capturedUrl = "";
    server.use(
      http.get("*/api/v1/realty-groups", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(asPage([]));
      }),
    );

    renderWithProviders(<GroupsBrowseClient />);

    await waitFor(() => expect(capturedUrl).not.toBe(""));
    const url = new URL(capturedUrl);
    expect(url.searchParams.get("q")).toBe("mainland");
    expect(url.searchParams.get("sort")).toBe("NEWEST");

    // Input is seeded so the user sees what's in the URL.
    expect(
      (screen.getByPlaceholderText(/search groups/i) as HTMLInputElement).value,
    ).toBe("mainland");
  });
});
