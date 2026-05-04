import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { CompletedStateCard } from "./CompletedStateCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("CompletedStateCard", () => {
  describe("seller view", () => {
    it("renders payout + commission amounts", () => {
      renderWithProviders(
        <CompletedStateCard
          escrow={fakeEscrow({
            state: "COMPLETED",
            payoutAmt: 4750,
            commissionAmt: 250,
            completedAt: "2026-05-02T10:00:05Z",
          })}
          role="seller"
        />,
      );
      expect(screen.getByText(/payout of l\$\s*4,?750/i)).toBeInTheDocument();
      expect(screen.getByText(/commission l\$\s*250/i)).toBeInTheDocument();
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
