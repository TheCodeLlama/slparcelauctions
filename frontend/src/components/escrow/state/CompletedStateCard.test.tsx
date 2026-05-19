import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { CompletedStateCard } from "./CompletedStateCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("CompletedStateCard", () => {
  describe("seller view (individual sale)", () => {
    it("renders the payout headline + sale-price / fee / net breakdown", () => {
      renderWithProviders(
        <CompletedStateCard
          escrow={fakeEscrow({
            state: "COMPLETED",
            finalBidAmount: 5000,
            payoutAmt: 4750,
            commissionAmt: 250,
            completedAt: "2026-05-02T10:00:05Z",
          })}
          role="seller"
        />,
      );

      // Headline keeps the net "Payout of L$X sent" so the eye lands on
      // the amount that actually moved to the seller's avatar.
      expect(screen.getByText(/payout of l\$\s*4,?750/i)).toBeInTheDocument();

      // Breakdown body: sale price anchor + SLParcels fee row + net.
      // Use queryAllByText for "L$ 4,750" since it shows up in both the
      // headline and the net row.
      expect(screen.getByText(/^sale price$/i)).toBeInTheDocument();
      expect(screen.getByText(/^l\$\s*5,?000$/i)).toBeInTheDocument();
      expect(
        screen.getByText(/slparcels fee \(5%, l\$50 min\)/i),
      ).toBeInTheDocument();
      expect(screen.getByText(/^.{1,2}l\$\s*250$/i)).toBeInTheDocument();
      expect(screen.getByText(/^your payout$/i)).toBeInTheDocument();

      // Old "Commission L$N" copy is gone — the term is reserved for the
      // agent-commission slice on group listings now.
      expect(screen.queryByText(/^commission l\$/i)).not.toBeInTheDocument();
    });

    it("shows the L$50-floor case correctly for small sales", () => {
      // L$100 sale → 5% would be L$5 but the floor lands the fee at L$50,
      // so the seller nets half. This is the scenario that prompted the
      // breakdown — make sure the math is legible.
      renderWithProviders(
        <CompletedStateCard
          escrow={fakeEscrow({
            state: "COMPLETED",
            finalBidAmount: 100,
            payoutAmt: 50,
            commissionAmt: 50,
            completedAt: "2026-05-19T14:40:00Z",
          })}
          role="seller"
        />,
      );
      expect(screen.getByText(/payout of l\$\s*50/i)).toBeInTheDocument();
      expect(screen.getByText(/^l\$\s*100$/i)).toBeInTheDocument(); // sale price row
      expect(
        screen.getByText(/slparcels fee \(5%, l\$50 min\)/i),
      ).toBeInTheDocument();
    });

    it("does not render winner-only copy", () => {
      renderWithProviders(
        <CompletedStateCard
          escrow={fakeEscrow({ state: "COMPLETED" })}
          role="seller"
        />,
      );
      expect(
        screen.queryByText(/parcel transferred/i),
      ).not.toBeInTheDocument();
    });
  });

  describe("seller view (group listing, payoutAmt = 0)", () => {
    it("shows 'Sale completed' acknowledgement without the Payout-of-L$0 headline", () => {
      renderWithProviders(
        <CompletedStateCard
          escrow={fakeEscrow({
            state: "COMPLETED",
            finalBidAmount: 5000,
            payoutAmt: 0,
            commissionAmt: 250,
            completedAt: "2026-05-02T10:00:05Z",
          })}
          role="seller"
        />,
      );
      expect(screen.getByText(/sale completed/i)).toBeInTheDocument();
      expect(screen.getByText(/sale price: l\$\s*5,?000/i)).toBeInTheDocument();
      expect(
        screen.getByText(/payout was split to the listing agent and the group wallet/i),
      ).toBeInTheDocument();
      // The misleading "Payout of L$0 sent" headline must NOT appear here.
      expect(
        screen.queryByText(/payout of l\$\s*0/i),
      ).not.toBeInTheDocument();
    });
  });

  describe("winner view", () => {
    it("renders parcel-transferred copy with completion timestamp", () => {
      renderWithProviders(
        <CompletedStateCard
          escrow={fakeEscrow({
            state: "COMPLETED",
            completedAt: "2026-05-02T10:00:05Z",
          })}
          role="winner"
        />,
      );
      expect(screen.getByText(/parcel transferred/i)).toBeInTheDocument();
      expect(screen.getByText(/has been transferred to you/i)).toBeInTheDocument();
    });

    it("does not render seller payout copy", () => {
      renderWithProviders(
        <CompletedStateCard
          escrow={fakeEscrow({ state: "COMPLETED" })}
          role="winner"
        />,
      );
      expect(screen.queryByText(/payout of l\$/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/commission l\$/i)).not.toBeInTheDocument();
    });
  });

  it("does not render a dispute link in either role", () => {
    renderWithProviders(
      <CompletedStateCard
        escrow={fakeEscrow({ state: "COMPLETED" })}
        role="seller"
      />,
    );
    expect(
      screen.queryByRole("link", { name: /file a dispute/i }),
    ).not.toBeInTheDocument();
  });
});
