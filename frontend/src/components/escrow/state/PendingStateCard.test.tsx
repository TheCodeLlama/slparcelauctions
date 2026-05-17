import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { PendingStateCard } from "./PendingStateCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

/**
 * Post wallet-only-escrow spec (2026-05-16), ESCROW_PENDING is a
 * transactional intermediate inside createForEndedAuction — it never
 * persists past commit. The card renders a passive "funding in progress"
 * status for legacy historical rows only; no terminal CTA, no dispute
 * link, no payment-deadline badge (column was dropped).
 */
describe("PendingStateCard", () => {
  it("renders passive 'funding in progress' copy for both roles", () => {
    for (const role of ["seller", "winner"] as const) {
      const { unmount } = renderWithProviders(
        <PendingStateCard
          escrow={fakeEscrow({ state: "ESCROW_PENDING", finalBidAmount: 5000 })}
          role={role}
        />,
      );
      expect(screen.getByText(/escrow funding in progress/i)).toBeInTheDocument();
      expect(screen.getByText(/L\$\s*5,?000/i)).toBeInTheDocument();
      expect(
        screen.getByText(/transferred from the winner's slparcels wallet/i),
      ).toBeInTheDocument();
      // Terminal affordance, dispute link, and "pay" CTA are all gone.
      expect(screen.queryByText(/find a terminal/i)).not.toBeInTheDocument();
      expect(screen.queryByRole("link", { name: /file a dispute/i })).not.toBeInTheDocument();
      expect(screen.queryByText(/pay escrow/i)).not.toBeInTheDocument();
      unmount();
    }
  });

  it("carries the data-state / data-role hooks for downstream tests", () => {
    renderWithProviders(
      <PendingStateCard
        escrow={fakeEscrow({ state: "ESCROW_PENDING" })}
        role="winner"
      />,
    );
    const card = screen.getByTestId("escrow-state-card");
    expect(card).toHaveAttribute("data-state", "ESCROW_PENDING");
    expect(card).toHaveAttribute("data-role", "winner");
  });
});
