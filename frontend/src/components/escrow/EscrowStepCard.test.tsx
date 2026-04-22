import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { EscrowStepCard } from "./EscrowStepCard";
import { fakeEscrow } from "@/test/fixtures/escrow";
import type { EscrowState } from "@/types/escrow";

describe("EscrowStepCard", () => {
  const cases: Array<{
    state: EscrowState;
    expected: string;
  }> = [
    { state: "ESCROW_PENDING", expected: "ESCROW_PENDING" },
    { state: "FUNDED", expected: "TRANSFER_PENDING" },
    { state: "TRANSFER_PENDING", expected: "TRANSFER_PENDING" },
    { state: "COMPLETED", expected: "COMPLETED" },
    { state: "DISPUTED", expected: "DISPUTED" },
    { state: "FROZEN", expected: "FROZEN" },
    { state: "EXPIRED", expected: "EXPIRED" },
  ];

  it.each(cases)(
    "routes state=$state to a card with data-state=$expected",
    ({ state, expected }) => {
      renderWithProviders(
        <EscrowStepCard
          escrow={fakeEscrow({
            state,
            // Minimal fields required by the routed card to render without errors
            fundedAt:
              state === "TRANSFER_PENDING" || state === "FUNDED"
                ? "2026-04-30T12:00:00Z"
                : null,
            transferDeadline:
              state === "TRANSFER_PENDING" || state === "FUNDED"
                ? "2026-05-03T12:00:00Z"
                : null,
            disputedAt: state === "DISPUTED" ? "2026-05-01T10:00:00Z" : null,
            disputeReasonCategory: state === "DISPUTED" ? "OTHER" : null,
            frozenAt: state === "FROZEN" ? "2026-05-01T10:00:00Z" : null,
            freezeReason: state === "FROZEN" ? "UNKNOWN_OWNER" : null,
            expiredAt: state === "EXPIRED" ? "2026-05-03T12:00:00Z" : null,
          })}
          role="winner"
        />,
      );
      const card = screen.getByTestId("escrow-state-card");
      expect(card).toHaveAttribute("data-state", expected);
    },
  );

  it("threads role through to the routed card", () => {
    renderWithProviders(
      <EscrowStepCard
        escrow={fakeEscrow({ state: "ESCROW_PENDING" })}
        role="seller"
      />,
    );
    const card = screen.getByTestId("escrow-state-card");
    expect(card).toHaveAttribute("data-role", "seller");
  });
});
