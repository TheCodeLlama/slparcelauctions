import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { EscrowChip } from "./EscrowChip";

describe("EscrowChip", () => {
  describe("ESCROW_PENDING (legacy transactional intermediate)", () => {
    // Wallet-only escrow spec (2026-05-16): both roles see "Escrow pending"
    // with waiting tone. The state never persists past commit in production
    // post-spec; chip copy applies only to legacy historical rows.
    it.each(["winner", "seller", undefined] as const)(
      "renders 'Escrow pending' with waiting tone (role=%s)",
      (role) => {
        const { unmount } = renderWithProviders(
          <EscrowChip state="ESCROW_PENDING" role={role} />,
        );
        const chip = screen.getByText(/escrow pending/i);
        expect(chip).toBeInTheDocument();
        expect(chip).toHaveAttribute("data-tone", "waiting");
        unmount();
      },
    );
  });

  describe("TRANSFER_PENDING sub-split", () => {
    it("shows AWAITING TRANSFER for winner pre-confirmation", () => {
      renderWithProviders(
        <EscrowChip
          state="TRANSFER_PENDING"
          role="winner"
          transferConfirmedAt={null}
        />,
      );
      expect(screen.getByText(/awaiting transfer/i)).toBeInTheDocument();
    });
    it("shows TRANSFER LAND for seller pre-confirmation", () => {
      renderWithProviders(
        <EscrowChip
          state="TRANSFER_PENDING"
          role="seller"
          transferConfirmedAt={null}
        />,
      );
      expect(screen.getByText(/transfer land/i)).toBeInTheDocument();
    });
    it("shows PAYOUT PENDING for both roles post-confirmation", () => {
      const { rerender } = renderWithProviders(
        <EscrowChip
          state="TRANSFER_PENDING"
          role="winner"
          transferConfirmedAt={new Date().toISOString()}
        />,
      );
      expect(screen.getByText(/payout pending/i)).toBeInTheDocument();
      rerender(
        <EscrowChip
          state="TRANSFER_PENDING"
          role="seller"
          transferConfirmedAt={new Date().toISOString()}
        />,
      );
      expect(screen.getByText(/payout pending/i)).toBeInTheDocument();
    });
  });

  describe("terminal states", () => {
    it("shows COMPLETED in done tone", () => {
      renderWithProviders(<EscrowChip state="COMPLETED" role="winner" />);
      expect(screen.getByText(/completed/i)).toHaveAttribute(
        "data-tone",
        "done",
      );
    });
    it("shows DISPUTED in problem tone", () => {
      renderWithProviders(<EscrowChip state="DISPUTED" role="winner" />);
      expect(screen.getByText(/disputed/i)).toHaveAttribute(
        "data-tone",
        "problem",
      );
    });
    it("shows FROZEN in problem tone", () => {
      renderWithProviders(<EscrowChip state="FROZEN" role="winner" />);
      expect(screen.getByText(/frozen/i)).toHaveAttribute(
        "data-tone",
        "problem",
      );
    });
    it("shows EXPIRED in muted tone", () => {
      renderWithProviders(<EscrowChip state="EXPIRED" role="winner" />);
      expect(screen.getByText(/expired/i)).toHaveAttribute(
        "data-tone",
        "muted",
      );
    });
  });

});
