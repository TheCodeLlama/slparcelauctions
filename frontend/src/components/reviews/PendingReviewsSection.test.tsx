import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type { PendingReviewDto } from "@/types/review";
import {
  PendingReviewsSection,
  formatWindowRemaining,
} from "./PendingReviewsSection";

function makePending(
  overrides: Partial<PendingReviewDto> = {},
): PendingReviewDto {
  return {
    auctionPublicId: "00000000-0000-0000-0000-0000000004d2",
    title: "Aurora Parcel",
    primaryPhotoUrl: null,
    counterpartyPublicId: "00000000-0000-0000-0000-000000000007",
    counterpartyDisplayName: "Alice",
    counterpartyAvatarUrl: null,
    escrowCompletedAt: "2026-04-18T00:00:00Z",
    windowClosesAt: "2026-05-02T00:00:00Z",
    hoursRemaining: 72,
    viewerRole: "SELLER",
    ...overrides,
  };
}

describe("formatWindowRemaining", () => {
  it("returns days when ≥ 24h remain", () => {
    expect(formatWindowRemaining(72)).toBe("Closes in 3 days");
    expect(formatWindowRemaining(24)).toBe("Closes in 1 day");
  });

  it("returns hours when < 24h remain", () => {
    expect(formatWindowRemaining(23)).toBe("Closes in 23 hours");
    expect(formatWindowRemaining(1)).toBe("Closes in 1 hour");
  });

  it("collapses to 'Closes soon' when < 1h remain", () => {
    expect(formatWindowRemaining(0)).toBe("Closes soon");
    expect(formatWindowRemaining(-1)).toBe("Closes soon");
  });

  it("floors fractional hours before choosing the unit", () => {
    expect(formatWindowRemaining(23.9)).toBe("Closes in 23 hours");
    expect(formatWindowRemaining(48.5)).toBe("Closes in 2 days");
  });
});

describe("PendingReviewsSection", () => {
  it("renders nothing when the list is empty", async () => {
    server.use(
      http.get("*/api/v1/users/me/pending-reviews", () =>
        HttpResponse.json([]),
      ),
    );

    renderWithProviders(<PendingReviewsSection />, {
      auth: "authenticated",
    });

    // Wait a tick for the query to resolve before asserting the
    // nothing-rendered contract.
    await waitFor(() => {
      expect(
        screen.queryByTestId("pending-reviews-section"),
      ).not.toBeInTheDocument();
    });
    expect(screen.queryByText("Pending reviews")).not.toBeInTheDocument();
  });

  it("renders one row per pending review with CTA link and window copy", async () => {
    server.use(
      http.get("*/api/v1/users/me/pending-reviews", () =>
        HttpResponse.json([
          makePending({ auctionPublicId: "00000000-0000-0000-0000-0000000004d2", hoursRemaining: 72, viewerRole: "SELLER" }),
          makePending({
            auctionPublicId: "00000000-0000-0000-0000-00000000162e",
            title: "Lakeview Shore",
            counterpartyDisplayName: "Bob",
            hoursRemaining: 12,
            viewerRole: "BUYER",
          }),
        ]),
      ),
    );

    renderWithProviders(<PendingReviewsSection />, { auth: "authenticated" });

    expect(await screen.findByText("Pending reviews")).toBeInTheDocument();
    const rows = screen.getAllByTestId("pending-review-row");
    expect(rows).toHaveLength(2);

    // First row — SELLER, 3 days remaining
    expect(screen.getByText("Aurora Parcel")).toBeInTheDocument();
    expect(
      screen.getByText("Leave a review for Alice"),
    ).toBeInTheDocument();
    expect(screen.getByText("Closes in 3 days")).toBeInTheDocument();
    expect(screen.getByText("· Seller")).toBeInTheDocument();

    // Second row — BUYER, 12 hours remaining
    expect(screen.getByText("Lakeview Shore")).toBeInTheDocument();
    expect(screen.getByText("Leave a review for Bob")).toBeInTheDocument();
    expect(screen.getByText("Closes in 12 hours")).toBeInTheDocument();
    expect(screen.getByText("· Buyer")).toBeInTheDocument();
  });

  it("links each CTA to the escrow page's #review-panel anchor", async () => {
    server.use(
      http.get("*/api/v1/users/me/pending-reviews", () =>
        HttpResponse.json([makePending({ auctionPublicId: "00000000-0000-0000-0000-00000000002a" })]),
      ),
    );

    renderWithProviders(<PendingReviewsSection />, { auth: "authenticated" });

    const cta = await screen.findByTestId("pending-review-cta");
    expect(cta).toHaveAttribute("href", "/auction/00000000-0000-0000-0000-00000000002a/escrow#review-panel");
  });

  it("renders nothing when the request fails", async () => {
    server.use(
      http.get("*/api/v1/users/me/pending-reviews", () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );

    renderWithProviders(<PendingReviewsSection />, {
      auth: "authenticated",
    });

    await waitFor(() => {
      expect(
        screen.queryByTestId("pending-reviews-section"),
      ).not.toBeInTheDocument();
    });
    expect(screen.queryByText("Pending reviews")).not.toBeInTheDocument();
  });
});
