import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminDisputesHandlers } from "@/test/msw/handlers";
import { AdminDisputesListPage } from "./AdminDisputesListPage";
import type { AdminDisputeQueueRow } from "@/lib/admin/disputes";

function makeRow(overrides: Partial<AdminDisputeQueueRow> = {}): AdminDisputeQueueRow {
  return {
    escrowId: 1,
    auctionId: 100,
    auctionTitle: "Test Parcel",
    sellerEmail: "seller@example.com",
    winnerEmail: "winner@example.com",
    salePriceL: 500,
    status: "DISPUTED",
    reasonCategory: "PAYMENT_NOT_CREDITED",
    openedAt: "2026-04-24T14:22:00Z",
    ageMinutes: 100,
    winnerEvidenceCount: 1,
    sellerEvidenceCount: 0,
    ...overrides,
  };
}

describe("AdminDisputesListPage", () => {
  it("renders empty state when no disputes", async () => {
    server.use(adminDisputesHandlers.listEmpty());
    renderWithProviders(<AdminDisputesListPage />);
    expect(await screen.findByText(/No disputes in this view/i)).toBeInTheDocument();
  });

  it("renders rows when disputes exist", async () => {
    server.use(
      adminDisputesHandlers.listWithItems([
        makeRow({
          escrowId: 1,
          auctionId: 100,
          auctionTitle: "Beachfront 1024m²",
          sellerEmail: "seller@example.com",
          winnerEmail: "winner@example.com",
          salePriceL: 1031,
          status: "DISPUTED",
          reasonCategory: "PAYMENT_NOT_CREDITED",
          openedAt: "2026-04-24T14:22:00Z",
          ageMinutes: 4500,
          winnerEvidenceCount: 3,
          sellerEvidenceCount: 0,
        }),
      ])
    );
    renderWithProviders(<AdminDisputesListPage />);
    expect(await screen.findByText("Beachfront 1024m²")).toBeInTheDocument();
  });

  it("renders frozen status row", async () => {
    server.use(
      adminDisputesHandlers.listWithItems([
        makeRow({ escrowId: 2, status: "FROZEN", auctionTitle: "Frozen Parcel" }),
      ])
    );
    renderWithProviders(<AdminDisputesListPage />);
    expect(await screen.findByText("Frozen Parcel")).toBeInTheDocument();
    // "❄ Frozen" appears in both the filter chip and the table status cell
    expect(screen.getAllByText("❄ Frozen").length).toBeGreaterThanOrEqual(2);
  });
});
