import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminEscrowReviewsHandlers } from "@/test/msw/handlers";
import { AdminEscrowReviewsListPage } from "./AdminEscrowReviewsListPage";
import type { AdminEscrowReviewRow } from "@/lib/admin/escrowReviews";

function makeRow(
  overrides: Partial<AdminEscrowReviewRow> = {},
): AdminEscrowReviewRow {
  return {
    reviewPublicId: "00000000-0000-0000-0000-000000000001",
    escrowPublicId: "00000000-0000-0000-0000-0000000000e1",
    auctionPublicId: "00000000-0000-0000-0000-0000000000a1",
    parcelName: "Test Parcel",
    step: "SET_SELL_TO",
    reason: "USER_REQUESTED",
    status: "OPEN",
    requestedRole: "SELLER",
    createdAt: "2026-05-17T14:22:00Z",
    ageMinutes: 100,
    ...overrides,
  };
}

describe("AdminEscrowReviewsListPage", () => {
  it("renders empty state when no escrow reviews", async () => {
    server.use(adminEscrowReviewsHandlers.listEmpty());
    renderWithProviders(<AdminEscrowReviewsListPage />);
    expect(
      await screen.findByText(/No escrow reviews in this view/i),
    ).toBeInTheDocument();
  });

  it("renders rows when escrow reviews exist", async () => {
    server.use(
      adminEscrowReviewsHandlers.listWithItems([
        makeRow({
          parcelName: "Beachfront 1024m²",
          step: "BUY_PARCEL",
          reason: "BOT_PERSISTENT_FAILURE",
          ageMinutes: 4500,
        }),
      ]),
    );
    renderWithProviders(<AdminEscrowReviewsListPage />);
    expect(await screen.findByText("Beachfront 1024m²")).toBeInTheDocument();
    expect(screen.getByText("Buy Parcel")).toBeInTheDocument();
    expect(screen.getByText("Bot persistent failure")).toBeInTheDocument();
  });

  it("renders the OPEN status in the table", async () => {
    server.use(
      adminEscrowReviewsHandlers.listWithItems([
        makeRow({ status: "OPEN", parcelName: "Open Review Parcel" }),
      ]),
    );
    renderWithProviders(<AdminEscrowReviewsListPage />);
    expect(await screen.findByText("Open Review Parcel")).toBeInTheDocument();
    // The filter chip reads "Open"; the table status cell shows the raw
    // enum value "OPEN" linked to the detail route.
    expect(screen.getByText("Open")).toBeInTheDocument();
    expect(screen.getByText("OPEN")).toBeInTheDocument();
  });
});
