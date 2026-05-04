import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import userEvent from "@testing-library/user-event";
import { server } from "@/test/msw/server";
import type { MyBidSummary } from "@/types/auction";
import type { Page } from "@/types/page";
import { MyBidsTab } from "./MyBidsTab";

const mockReplace = vi.fn();
let currentSearchParams = new URLSearchParams();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
  usePathname: () => "/dashboard/bids",
  useSearchParams: () => currentSearchParams,
}));

function summary(
  id: number,
  overrides: Partial<MyBidSummary> = {},
): MyBidSummary {
  const publicId = `00000000-0000-0000-0000-${String(id).padStart(12, "0")}`;
  return {
    auction: {
      publicId,
      status: "ACTIVE",
      endOutcome: null,
      parcelName: `Parcel ${id}`,
      parcelRegion: "Heterocera",
      parcelAreaSqm: 1024,
      snapshotUrl: null,
      endsAt: new Date(Date.now() + 3_600_000).toISOString(),
      endedAt: null,
      currentBid: 1000 * id,
      bidderCount: 1,
      sellerPublicId: "00000000-0000-0000-0000-000000000007",
      sellerDisplayName: "Seller",
    },
    myHighestBidAmount: 500 * id,
    myHighestBidAt: "2026-04-20T12:00:00Z",
    myProxyMaxAmount: null,
    myBidStatus: "WINNING",
    ...overrides,
  };
}

function page(
  content: MyBidSummary[],
  overrides: Partial<Page<MyBidSummary>> = {},
): Page<MyBidSummary> {
  return {
    content,
    totalElements: content.length,
    totalPages: Math.max(1, Math.ceil(content.length / 20)),
    number: 0,
    size: 20,
    ...overrides,
  };
}

describe("MyBidsTab", () => {
  beforeEach(() => {
    mockReplace.mockReset();
    currentSearchParams = new URLSearchParams();
  });

  it("renders a list of my bids when the query resolves", async () => {
    server.use(
      http.get("*/api/v1/users/me/bids", () =>
        HttpResponse.json(page([summary(1), summary(2)])),
      ),
    );
    renderWithProviders(<MyBidsTab />, { auth: "authenticated" });

    await waitFor(() => {
      expect(screen.getByText("Parcel 1")).toBeInTheDocument();
      expect(screen.getByText("Parcel 2")).toBeInTheDocument();
    });
  });

  it("reads the status filter from the URL search params", async () => {
    currentSearchParams = new URLSearchParams("status=won");
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/me/bids", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(page([summary(5, { myBidStatus: "WON" })]));
      }),
    );
    renderWithProviders(<MyBidsTab />, { auth: "authenticated" });

    await waitFor(() => {
      expect(captured).not.toBeNull();
      expect(captured!.searchParams.get("status")).toBe("won");
    });
    expect(screen.getByRole("radio", { name: "Won" })).toHaveAttribute(
      "aria-checked",
      "true",
    );
  });

  it("router.replaces on filter tab click", async () => {
    server.use(
      http.get("*/api/v1/users/me/bids", () =>
        HttpResponse.json(page([])),
      ),
    );
    renderWithProviders(<MyBidsTab />, { auth: "authenticated" });

    await waitFor(() => {
      expect(screen.getByText("No bids yet")).toBeInTheDocument();
    });

    await userEvent.click(screen.getByRole("radio", { name: "Active" }));
    expect(mockReplace).toHaveBeenCalledWith("/dashboard/bids?status=active");
  });

  it("strips status=all from the URL when returning to the All tab", async () => {
    currentSearchParams = new URLSearchParams("status=lost");
    server.use(
      http.get("*/api/v1/users/me/bids", () =>
        HttpResponse.json(page([])),
      ),
    );
    renderWithProviders(<MyBidsTab />, { auth: "authenticated" });

    await waitFor(() => {
      expect(
        screen.getByText("Nothing in the Lost column"),
      ).toBeInTheDocument();
    });

    await userEvent.click(screen.getByRole("radio", { name: "All" }));
    expect(mockReplace).toHaveBeenCalledWith("/dashboard/bids");
  });

  it("renders per-filter empty state copy when content is empty", async () => {
    currentSearchParams = new URLSearchParams("status=won");
    server.use(
      http.get("*/api/v1/users/me/bids", () =>
        HttpResponse.json(page([])),
      ),
    );
    renderWithProviders(<MyBidsTab />, { auth: "authenticated" });

    await waitFor(() => {
      expect(screen.getByText("No won auctions yet")).toBeInTheDocument();
    });
  });

  it("shows Load more when loaded count is less than totalElements", async () => {
    server.use(
      http.get("*/api/v1/users/me/bids", () =>
        HttpResponse.json(
          page([summary(1)], { totalElements: 2, totalPages: 2 }),
        ),
      ),
    );
    renderWithProviders(<MyBidsTab />, { auth: "authenticated" });

    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /load more/i }),
      ).toBeInTheDocument();
    });
  });

  it("hides Load more when all rows are loaded", async () => {
    server.use(
      http.get("*/api/v1/users/me/bids", () =>
        HttpResponse.json(page([summary(1), summary(2)])),
      ),
    );
    renderWithProviders(<MyBidsTab />, { auth: "authenticated" });

    await waitFor(() => {
      expect(screen.getByText("Parcel 1")).toBeInTheDocument();
    });
    expect(
      screen.queryByRole("button", { name: /load more/i }),
    ).not.toBeInTheDocument();
  });

  it("renders an error message when the query fails", async () => {
    server.use(
      http.get("*/api/v1/users/me/bids", () =>
        HttpResponse.json(
          { title: "Boom", detail: "Something went wrong" },
          { status: 500 },
        ),
      ),
    );
    renderWithProviders(<MyBidsTab />, { auth: "authenticated" });

    await waitFor(() => {
      expect(screen.getByText(/Something went wrong/i)).toBeInTheDocument();
    });
  });
});
