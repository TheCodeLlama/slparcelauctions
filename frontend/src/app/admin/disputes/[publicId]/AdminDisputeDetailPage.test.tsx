import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminDisputesHandlers } from "@/test/msw/handlers";
import { AdminDisputeDetailPage } from "./AdminDisputeDetailPage";
import type { AdminDisputeDetail } from "@/lib/admin/disputes";

const baseDetail: AdminDisputeDetail = {
  escrowId: 47, auctionId: 100, auctionTitle: "Beachfront 1024m²",
  sellerEmail: "seller@example.com", sellerUserId: 7,
  winnerEmail: "winner@example.com", winnerUserId: 42,
  salePriceL: 1031, status: "DISPUTED",
  reasonCategory: "PAYMENT_NOT_CREDITED",
  winnerDescription: "Paid but ledger missed it",
  slTransactionKey: "abc-123",
  winnerEvidence: [],
  sellerEvidenceText: null, sellerEvidenceSubmittedAt: null,
  sellerEvidence: [],
  openedAt: "2026-04-24T14:22:00Z",
  ledger: [],
};

describe("AdminDisputeDetailPage", () => {
  it("renders the dispute header", async () => {
    server.use(adminDisputesHandlers.detail(47, baseDetail));
    renderWithProviders(<AdminDisputeDetailPage escrowId={47} />);
    expect(await screen.findByRole("heading", { name: "Beachfront 1024m²" })).toBeInTheDocument();
    expect(screen.getByText("⚐ DISPUTED")).toBeInTheDocument();
  });

  it("hides cancel checkbox when RECOGNIZE_PAYMENT is selected", async () => {
    server.use(adminDisputesHandlers.detail(47, baseDetail));
    renderWithProviders(<AdminDisputeDetailPage escrowId={47} />);
    // RESET_TO_FUNDED is default → checkbox visible
    expect(await screen.findByText(/Also cancel this listing/i)).toBeInTheDocument();

    const recognizeRadio = screen.getByLabelText(/Recognize payment/);
    await userEvent.click(recognizeRadio);
    expect(screen.queryByText(/Also cancel this listing/i)).not.toBeInTheDocument();
  });

  it("disables apply button when admin note is empty", async () => {
    server.use(adminDisputesHandlers.detail(47, baseDetail));
    renderWithProviders(<AdminDisputeDetailPage escrowId={47} />);
    const button = await screen.findByRole("button", { name: /apply resolution/i });
    expect(button).toBeDisabled();
  });

  it("renders FROZEN actions for FROZEN escrows", async () => {
    server.use(adminDisputesHandlers.detail(47, { ...baseDetail, status: "FROZEN", reasonCategory: null }));
    renderWithProviders(<AdminDisputeDetailPage escrowId={47} />);
    expect(await screen.findByLabelText(/Resume transfer/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Mark expired/)).toBeInTheDocument();
  });
});
