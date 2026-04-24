import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type { PublicAuctionResponse } from "@/types/auction";
import type { Page } from "@/types/page";
import { ActiveListingsSection } from "./ActiveListingsSection";

function listing(id: number, overrides: Partial<PublicAuctionResponse> = {}): PublicAuctionResponse {
  return {
    id,
    sellerId: 100,
    title: "Featured Parcel Listing",
    parcel: {
      id,
      slParcelUuid: `00000000-0000-0000-0000-00000000000${id}`,
      ownerUuid: "aaaa1111-0000-0000-0000-000000000000",
      ownerType: "agent",
      regionName: "Heterocera",
      gridX: 0,
      gridY: 0,
      positionX: 128,
      positionY: 128,
      positionZ: 0,
      continentName: null,
      areaSqm: 1024,
      description: `Parcel ${id}`,
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: true,
      verifiedAt: "2026-04-20T00:00:00Z",
      lastChecked: "2026-04-20T00:00:00Z",
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "ACTIVE",
    verificationTier: "SCRIPT",
    startingBid: 500,
    hasReserve: false,
    reserveMet: true,
    buyNowPrice: null,
    currentBid: 1500,
    bidCount: 3,
    currentHighBid: 1500,
    bidderCount: 2,
    durationHours: 72,
    snipeProtect: true,
    snipeWindowMin: 10,
    startsAt: "2026-04-19T00:00:00Z",
    endsAt: new Date(Date.now() + 3_600_000).toISOString(),
    originalEndsAt: "2026-04-21T00:00:00Z",
    sellerDesc: null,
    tags: [],
    photos: [],
    ...overrides,
  };
}

function page(
  content: PublicAuctionResponse[],
  overrides: Partial<Page<PublicAuctionResponse>> = {},
): Page<PublicAuctionResponse> {
  return {
    content,
    totalElements: content.length,
    totalPages: Math.max(1, Math.ceil(content.length / 6)),
    number: 0,
    size: 6,
    ...overrides,
  };
}

describe("ActiveListingsSection", () => {
  it("renders a grid of listing cards from the user-scoped endpoint", async () => {
    server.use(
      http.get("*/api/v1/users/:userId/auctions", () =>
        HttpResponse.json(page([listing(1), listing(2), listing(3)])),
      ),
    );
    renderWithProviders(<ActiveListingsSection userId={42} />);

    await waitFor(() => {
      expect(screen.getByText("Parcel 1")).toBeInTheDocument();
    });
    expect(screen.getByText("Parcel 2")).toBeInTheDocument();
    expect(screen.getByText("Parcel 3")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: /view listing/i }).length).toBe(3);
  });

  it("sends status=ACTIVE and size=6 to the backend", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/:userId/auctions", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(page([]));
      }),
    );
    renderWithProviders(<ActiveListingsSection userId={42} />);

    await waitFor(() => {
      expect(captured).not.toBeNull();
    });
    expect(captured!.searchParams.get("status")).toBe("ACTIVE");
    expect(captured!.searchParams.get("size")).toBe("6");
  });

  it("shows the empty state when no active listings exist", async () => {
    server.use(
      http.get("*/api/v1/users/:userId/auctions", () =>
        HttpResponse.json(page([])),
      ),
    );
    renderWithProviders(<ActiveListingsSection userId={42} />);

    await waitFor(() => {
      expect(screen.getByText("No active listings")).toBeInTheDocument();
    });
  });

  it("shows the 'View all' link when totalElements > size", async () => {
    server.use(
      http.get("*/api/v1/users/:userId/auctions", () =>
        HttpResponse.json(
          page([listing(1), listing(2), listing(3), listing(4), listing(5), listing(6)], {
            totalElements: 10,
            totalPages: 2,
          }),
        ),
      ),
    );
    renderWithProviders(<ActiveListingsSection userId={42} />);

    await waitFor(() => {
      expect(screen.getByText(/View all \(10\)/)).toBeInTheDocument();
    });
    const viewAll = screen.getByRole("link", { name: /View all/ });
    expect(viewAll).toHaveAttribute("href", "/users/42/listings");
  });

  it("hides the 'View all' link when totalElements <= size", async () => {
    server.use(
      http.get("*/api/v1/users/:userId/auctions", () =>
        HttpResponse.json(page([listing(1), listing(2)])),
      ),
    );
    renderWithProviders(<ActiveListingsSection userId={42} />);

    await waitFor(() => {
      expect(screen.getByText("Parcel 1")).toBeInTheDocument();
    });
    expect(screen.queryByText(/View all/)).not.toBeInTheDocument();
  });

  it("renders an error empty-state when the fetch fails", async () => {
    server.use(
      http.get("*/api/v1/users/:userId/auctions", () =>
        HttpResponse.json({ title: "Boom" }, { status: 500 }),
      ),
    );
    renderWithProviders(<ActiveListingsSection userId={42} />);

    await waitFor(() => {
      expect(screen.getByText("Could not load listings")).toBeInTheDocument();
    });
  });
});
