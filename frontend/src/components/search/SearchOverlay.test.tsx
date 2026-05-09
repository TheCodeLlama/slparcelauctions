import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { act } from "@testing-library/react";
import { mockSuggestResponse } from "@/test/msw/fixtures";

const pushMock = vi.fn();

vi.mock("next/navigation", async () => {
  const actual = await vi.importActual<typeof import("next/navigation")>(
    "next/navigation",
  );
  return {
    ...actual,
    useRouter: () => ({
      push: pushMock,
      replace: vi.fn(),
      prefetch: vi.fn(),
      back: vi.fn(),
      forward: vi.fn(),
    }),
    usePathname: () => "/",
    useSearchParams: () => new URLSearchParams(),
  };
});

import { SearchOverlay } from "./SearchOverlay";

describe("SearchOverlay", () => {
  beforeEach(() => {
    pushMock.mockReset();
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  function renderOpen() {
    return renderWithProviders(<SearchOverlay open onClose={vi.fn()} />);
  }

  it("renders empty state when input is < 2 chars", async () => {
    renderOpen();
    const input = screen.getByPlaceholderText(/Search parcels/i);
    await act(async () => {
      await userEvent.type(input, "a");
    });
    expect(screen.queryByText("Listings")).toBeNull();
    expect(screen.queryByText(/No matches/i)).toBeNull();
  });

  it("renders listings + regions after debounce settles", async () => {
    server.use(
      http.get("*/api/v1/search/suggest", () =>
        HttpResponse.json(mockSuggestResponse()),
      ),
    );
    renderOpen();
    await act(async () => {
      await userEvent.type(screen.getByPlaceholderText(/Search parcels/i), "tula");
      vi.advanceTimersByTime(300);
    });
    await waitFor(() =>
      expect(screen.getByText("Premium Waterfront")).toBeInTheDocument(),
    );
    // "Tula" appears twice: once in the listing row's region label,
    // once in the regions group. Both are valid presence assertions.
    expect(screen.getAllByText("Tula").length).toBeGreaterThan(0);
  });

  it("clicking a listing row navigates to /auction/{publicId}", async () => {
    server.use(
      http.get("*/api/v1/search/suggest", () =>
        HttpResponse.json(mockSuggestResponse()),
      ),
    );
    renderOpen();
    await act(async () => {
      await userEvent.type(screen.getByPlaceholderText(/Search parcels/i), "tula");
      vi.advanceTimersByTime(300);
    });
    await userEvent.click(await screen.findByText("Premium Waterfront"));
    expect(pushMock).toHaveBeenCalledWith(
      "/auction/00000000-0000-0000-0000-000000000099",
    );
  });

  it("bare Enter routes to /browse?q={trimmed}", async () => {
    // Empty results so hasResults stays false and the bare-Enter path
    // fires regardless of debounce timing.
    server.use(
      http.get("*/api/v1/search/suggest", () =>
        HttpResponse.json({ listings: [], regions: [], totalListings: 0 }),
      ),
    );
    // Use real timers for this case — userEvent.type is naturally
    // sequential, and the bare-Enter path needs no debounce to fire.
    vi.useRealTimers();
    renderOpen();
    const input = screen.getByPlaceholderText(/Search parcels/i);
    await userEvent.type(input, "tula{Enter}");
    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/browse?q=tula"),
    );
  });

  it("renders no-matches copy when both groups are empty", async () => {
    server.use(
      http.get("*/api/v1/search/suggest", () =>
        HttpResponse.json({ listings: [], regions: [], totalListings: 0 }),
      ),
    );
    renderOpen();
    await act(async () => {
      await userEvent.type(screen.getByPlaceholderText(/Search parcels/i), "xyz");
      vi.advanceTimersByTime(300);
    });
    await waitFor(() =>
      expect(screen.getByText(/No matches for/i)).toBeInTheDocument(),
    );
  });

  it("renders 'Search is unavailable' on backend 5xx", async () => {
    server.use(
      http.get("*/api/v1/search/suggest", () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );
    renderOpen();
    await act(async () => {
      await userEvent.type(screen.getByPlaceholderText(/Search parcels/i), "tula");
      vi.advanceTimersByTime(300);
    });
    await waitFor(() =>
      expect(screen.getByText(/Search is unavailable/i)).toBeInTheDocument(),
    );
    // The browse fallback is always present so the user can still
    // escape to /browse?q= even when suggest is down.
    expect(screen.getByText(/Search \/browse/i)).toBeInTheDocument();
  });
});
