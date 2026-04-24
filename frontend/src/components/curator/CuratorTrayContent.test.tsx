import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { CuratorTrayContent } from "./CuratorTrayContent";
import type { AuctionSearchQuery, SearchResponse } from "@/types/search";

const emptySearch: SearchResponse = {
  content: [],
  page: 0,
  size: 24,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

beforeEach(() => {
  server.use(
    http.get("*/api/v1/me/saved/ids", () =>
      HttpResponse.json({ ids: [] }),
    ),
  );
});

describe("CuratorTrayContent", () => {
  it("renders the empty-state CTA when the saved list is empty on the default filter", async () => {
    server.use(
      http.get("*/api/v1/me/saved/auctions", () =>
        HttpResponse.json(emptySearch),
      ),
    );
    renderWithProviders(<CuratorTrayContent />, {
      auth: "authenticated",
    });
    await waitFor(() => {
      expect(
        screen.getByText(/save parcels to review them here/i),
      ).toBeInTheDocument();
    });
  });

  it("wires onQueryChange when controlled (URL-synced /saved page usage)", async () => {
    server.use(
      http.get("*/api/v1/me/saved/auctions", () =>
        HttpResponse.json(emptySearch),
      ),
    );
    const onQueryChange = vi.fn();
    const query: AuctionSearchQuery = {
      sort: "newest",
      statusFilter: "active_only",
    };
    renderWithProviders(
      <CuratorTrayContent query={query} onQueryChange={onQueryChange} />,
      { auth: "authenticated" },
    );
    // Find the status filter select and flip it.
    const statusLabel = await screen.findByLabelText("Status");
    await userEvent.selectOptions(statusLabel, "all");
    expect(onQueryChange).toHaveBeenCalled();
    const nextQuery = onQueryChange.mock.calls.at(-1)?.[0];
    expect(nextQuery.statusFilter).toBe("all");
  });

  it("dark-mode render does not crash", async () => {
    server.use(
      http.get("*/api/v1/me/saved/auctions", () =>
        HttpResponse.json(emptySearch),
      ),
    );
    renderWithProviders(<CuratorTrayContent />, {
      auth: "authenticated",
      theme: "dark",
      forceTheme: true,
    });
    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /your curator tray/i }),
      ).toBeInTheDocument();
    });
  });
});
