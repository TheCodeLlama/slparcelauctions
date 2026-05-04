import { describe, it, expect, beforeEach, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { act } from "@testing-library/react";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import type { BidHistoryEntry } from "@/types/auction";
import type { Page } from "@/types/page";
import { bidHistoryKey } from "@/hooks/useBidHistory";
import { BidHistoryList } from "./BidHistoryList";

// ws client is pulled in transitively by nothing here, but some test
// environments still load the module — stub it out to be safe.
vi.mock("@/lib/ws/client", () => ({
  subscribe: vi.fn(),
  subscribeToConnectionState: vi.fn(),
  getConnectionState: vi.fn(() => ({ status: "connected" })),
}));

function entry(overrides: Partial<BidHistoryEntry> = {}): BidHistoryEntry {
  return {
    bidPublicId: "00000000-0000-0000-0000-000000000001",
    userPublicId: "00000000-0000-0000-0000-00000000002a",
    bidderDisplayName: "Alice",
    amount: 1500,
    bidType: "MANUAL",
    snipeExtensionMinutes: null,
    newEndsAt: null,
    createdAt: new Date(Date.now() - 60_000).toISOString(),
    ...overrides,
  };
}

function pageOf(
  content: BidHistoryEntry[],
  overrides: Partial<Page<BidHistoryEntry>> = {},
): Page<BidHistoryEntry> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 20,
    ...overrides,
  };
}

/**
 * Wrapper that seeds {@code queryClient} with a given page 0 payload.
 * {@link renderWithProviders} builds its own client — we need to inject
 * ours so page 0 can render synchronously.
 */
function renderWithSeed(
  auctionPublicId: string,
  seed: Page<BidHistoryEntry>,
  client?: QueryClient,
) {
  const qc =
    client ??
    new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
  qc.setQueryData(bidHistoryKey(auctionPublicId, 0), seed);
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  }
  const utils = renderWithProviders(<BidHistoryList auctionPublicId={auctionPublicId} />, {
    // @ts-expect-error -- renderWithProviders uses its own wrapper; we
    // wrap externally to inject the specific client. The outer
    // ThemeProvider + ToastProvider still get applied via the default
    // wrapper, which is fine — React tolerates nested providers.
    wrapper: Wrapper,
  });
  return { ...utils, queryClient: qc };
}

describe("BidHistoryList", () => {
  beforeEach(() => {
    server.use(
      http.get("*/api/v1/auctions/:id/bids", ({ request }) => {
        const url = new URL(request.url);
        const page = Number(url.searchParams.get("page") ?? "0");
        if (page === 0) {
          return HttpResponse.json(
            pageOf([entry({ bidPublicId: "00000000-0000-0000-0000-000000000001", amount: 1500 })], {
              totalElements: 1,
              totalPages: 1,
            }),
          );
        }
        return HttpResponse.json(
          pageOf([], { totalElements: 0, totalPages: 1, number: page }),
        );
      }),
    );
  });

  it("renders an empty state when the seed is empty", () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    qc.setQueryData(
      bidHistoryKey("00000000-0000-0000-0000-000000000007", 0),
      pageOf([], { totalElements: 0, totalPages: 1 }),
    );
    function Wrapper({ children }: { children: ReactNode }) {
      return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
    }
    renderWithProviders(<BidHistoryList auctionPublicId="00000000-0000-0000-0000-000000000007" />, {
      // @ts-expect-error -- external wrapper override
      wrapper: Wrapper,
    });
    expect(screen.getByTestId("bid-history-empty")).toHaveTextContent(
      "No bids yet",
    );
  });

  it("renders seeded bid rows without hitting the network", () => {
    const seed = pageOf(
      [
        entry({ bidPublicId: "00000000-0000-0000-0000-000000000002", amount: 2000, bidderDisplayName: "Bob" }),
        entry({ bidPublicId: "00000000-0000-0000-0000-000000000001", amount: 1500, bidderDisplayName: "Alice" }),
      ],
      { totalElements: 2, totalPages: 1 },
    );
    renderWithSeed("00000000-0000-0000-0000-000000000007", seed);
    const rows = screen.getAllByTestId("bid-history-row");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveAttribute("data-bid-id", "00000000-0000-0000-0000-000000000002");
    expect(rows[1]).toHaveAttribute("data-bid-id", "00000000-0000-0000-0000-000000000001");
  });

  it("shows a 'Load more' button when more pages are available", async () => {
    const seed = pageOf(
      [entry({ bidPublicId: "00000000-0000-0000-0000-000000000014", amount: 2000 })],
      { totalElements: 40, totalPages: 2, size: 20 },
    );
    server.use(
      http.get("*/api/v1/auctions/:id/bids", ({ request }) => {
        const url = new URL(request.url);
        const page = Number(url.searchParams.get("page") ?? "0");
        if (page === 1) {
          return HttpResponse.json(
            pageOf(
              [entry({ bidPublicId: "00000000-0000-0000-0000-00000000000a", amount: 1000, bidderDisplayName: "Zoe" })],
              { totalElements: 40, totalPages: 2, number: 1, size: 20 },
            ),
          );
        }
        return HttpResponse.json(seed);
      }),
    );
    renderWithSeed("00000000-0000-0000-0000-000000000007", seed);

    const btn = screen.getByTestId("bid-history-load-more");
    await userEvent.click(btn);

    await waitFor(() =>
      expect(
        screen.getAllByTestId("bid-history-row").length,
      ).toBeGreaterThanOrEqual(2),
    );
    expect(screen.getByText("Zoe")).toBeInTheDocument();
  });

  it("reflects a cache merge on page 0 without refetching", async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    qc.setQueryData(
      bidHistoryKey("00000000-0000-0000-0000-000000000007", 0),
      pageOf([entry({ bidPublicId: "00000000-0000-0000-0000-000000000001", amount: 1500 })], {
        totalElements: 1,
        totalPages: 1,
      }),
    );
    function Wrapper({ children }: { children: ReactNode }) {
      return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
    }
    renderWithProviders(<BidHistoryList auctionPublicId="00000000-0000-0000-0000-000000000007" />, {
      // @ts-expect-error -- external wrapper override
      wrapper: Wrapper,
    });

    expect(screen.getAllByTestId("bid-history-row")).toHaveLength(1);

    // Simulate the AuctionDetailClient envelope merger writing page 0.
    act(() => {
      qc.setQueryData(
        bidHistoryKey("00000000-0000-0000-0000-000000000007", 0),
        pageOf(
          [
            entry({
              bidPublicId: "00000000-0000-0000-0000-000000000002",
              amount: 2000,
              bidderDisplayName: "Bob",
            }),
            entry({ bidPublicId: "00000000-0000-0000-0000-000000000001", amount: 1500 }),
          ],
          { totalElements: 2, totalPages: 1 },
        ),
      );
    });

    await waitFor(() =>
      expect(screen.getAllByTestId("bid-history-row")).toHaveLength(2),
    );

    // The new top row should be marked as animated — the effect flips
    // the data-animated flag in a post-commit microtask, so give it a
    // tick under real timers.
    await waitFor(() => {
      const top = screen.getAllByTestId("bid-history-row")[0];
      expect(top).toHaveAttribute("data-animated", "true");
    });
  });
});
