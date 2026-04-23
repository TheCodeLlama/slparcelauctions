import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { DisputedStateCard } from "./DisputedStateCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("DisputedStateCard", () => {
  it.each(["seller", "winner"] as const)(
    "renders the same role-neutral copy for role=%s",
    (role) => {
      renderWithProviders(
        <DisputedStateCard
          escrow={fakeEscrow({
            state: "DISPUTED",
            disputedAt: "2026-05-01T15:00:00Z",
            disputeReasonCategory: "SELLER_NOT_RESPONSIVE",
            disputeDescription: "Haven't heard back in 72 hours.",
          })}
          role={role}
        />,
      );
      expect(screen.getByText(/dispute filed/i)).toBeInTheDocument();
      expect(screen.getByText(/seller not responsive/i)).toBeInTheDocument();
      expect(
        screen.getByText(/haven't heard back in 72 hours/i),
      ).toBeInTheDocument();
      expect(
        screen.getByText(/review within 48 hours|48 hours/i),
      ).toBeInTheDocument();
    },
  );

  it("does not render a dispute link (already disputed)", () => {
    renderWithProviders(
      <DisputedStateCard
        escrow={fakeEscrow({
          state: "DISPUTED",
          disputedAt: "2026-05-01T15:00:00Z",
          disputeReasonCategory: "OTHER",
          disputeDescription: "Something went wrong.",
        })}
        role="seller"
      />,
    );
    expect(
      screen.queryByRole("link", { name: /file a dispute/i }),
    ).not.toBeInTheDocument();
  });

  it("renders a fallback when disputeDescription is missing", () => {
    renderWithProviders(
      <DisputedStateCard
        escrow={fakeEscrow({
          state: "DISPUTED",
          disputedAt: "2026-05-01T15:00:00Z",
          disputeReasonCategory: "OTHER",
          disputeDescription: null,
        })}
        role="winner"
      />,
    );
    // Card should still render without crashing.
    expect(screen.getByText(/dispute filed/i)).toBeInTheDocument();
  });
});
