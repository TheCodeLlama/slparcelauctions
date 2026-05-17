import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { renderWithProviders, screen, fireEvent, waitFor } from "@/test/render";
import { TransferPendingStateCard } from "./TransferPendingStateCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("TransferPendingStateCard", () => {
  describe("pre-confirmation, seller", () => {
    beforeEach(() => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date("2026-05-01T12:00:00Z"));
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    it("renders the 5-step SL viewer recipe", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
          })}
          role="seller"
        />,
      );
      expect(screen.getByText(/about land/i)).toBeInTheDocument();
      expect(screen.getByText(/sell land/i)).toBeInTheDocument();
      expect(screen.getByText(/l\$\s*0/i)).toBeInTheDocument();
      expect(screen.getByText(/confirm the sale/i)).toBeInTheDocument();
    });

    it("renders the deadline badge with transferDeadline", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
          })}
          role="seller"
        />,
      );
      expect(screen.getByText(/left$/i)).toBeInTheDocument();
    });

    it("renders a dispute link", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            auctionPublicId: "00000000-0000-0000-0000-00000000002a",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
          })}
          role="seller"
        />,
      );
      const link = screen.getByRole("link", { name: /file a dispute/i });
      expect(link).toHaveAttribute("href", "/auction/00000000-0000-0000-0000-00000000002a/escrow/dispute");
    });

    it("renders the winner's SL avatar name with a copy button", async () => {
      // Real timers for this test so waitFor + the promise-resolution
      // microtask the copy handler awaits can actually progress.
      vi.useRealTimers();
      const writeTextMock = vi.fn<(text: string) => Promise<void>>().mockResolvedValue(undefined);
      Object.defineProperty(navigator, "clipboard", {
        value: { writeText: writeTextMock },
        writable: true,
        configurable: true,
      });

      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
            winnerSlAvatarName: "Alice Bidder Resident",
          })}
          role="seller"
        />,
      );

      expect(screen.getByTestId("winner-sl-avatar-name")).toHaveTextContent(
        "Alice Bidder Resident",
      );
      const copyBtn = screen.getByTestId("copy-winner-sl-avatar-name-btn");
      expect(copyBtn).toBeInTheDocument();

      fireEvent.click(copyBtn);
      await waitFor(() => {
        expect(writeTextMock).toHaveBeenCalledWith("Alice Bidder Resident");
      });
      await waitFor(() => {
        expect(copyBtn).toHaveTextContent(/copied/i);
      });
    });

    it("falls back to generic step 3 copy when winnerSlAvatarName is null", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
            winnerSlAvatarName: null,
          })}
          role="seller"
        />,
      );

      expect(
        screen.queryByTestId("winner-sl-avatar-name"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByTestId("copy-winner-sl-avatar-name-btn"),
      ).not.toBeInTheDocument();
      expect(
        screen.getByText(/winner's avatar name/i),
      ).toBeInTheDocument();
    });
  });

  describe("pre-confirmation, winner", () => {
    beforeEach(() => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date("2026-05-01T12:00:00Z"));
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    it("renders the waiting-for-seller headline", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
          })}
          role="winner"
        />,
      );
      expect(
        screen.getByText(/waiting for seller to transfer the parcel/i),
      ).toBeInTheDocument();
    });

    it("renders the guidance thresholds", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
          })}
          role="winner"
        />,
      );
      // Guidance list header + three bullet labels
      expect(screen.getByText(/what you can do/i)).toBeInTheDocument();
      expect(screen.getAllByText(/message seller/i).length).toBeGreaterThan(0);
      expect(screen.getByText(/> 48 hours/i)).toBeInTheDocument();
    });

    it("renders a disabled 'Message seller' button (inert placeholder)", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
          })}
          role="winner"
        />,
      );
      const btn = screen.getByRole("button", { name: /message seller/i });
      expect(btn).toBeDisabled();
    });

    it("renders deadline badge + dispute link", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            auctionPublicId: "00000000-0000-0000-0000-00000000002a",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
          })}
          role="winner"
        />,
      );
      expect(screen.getByText(/left$/i)).toBeInTheDocument();
      const link = screen.getByRole("link", { name: /file a dispute/i });
      expect(link).toHaveAttribute("href", "/auction/00000000-0000-0000-0000-00000000002a/escrow/dispute");
    });
  });

  describe("post-confirmation (payout pending) — role-neutral", () => {
    it.each(["seller", "winner"] as const)(
      "renders the payout-pending copy for role=%s",
      (role) => {
        renderWithProviders(
          <TransferPendingStateCard
            escrow={fakeEscrow({
              state: "TRANSFER_PENDING",
              fundedAt: "2026-04-30T12:00:00Z",
              transferConfirmedAt: "2026-05-01T10:00:00Z",
              transferDeadline: "2026-05-03T12:00:00Z",
            })}
            role={role}
          />,
        );
        expect(
          screen.getByText(/ownership transferred to the winner/i),
        ).toBeInTheDocument();
        expect(screen.getByText(/finalizing the transaction/i)).toBeInTheDocument();
      },
    );

    it("does NOT render a dispute link post-confirmation", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: "2026-05-01T10:00:00Z",
          })}
          role="seller"
        />,
      );
      expect(
        screen.queryByRole("link", { name: /file a dispute/i }),
      ).not.toBeInTheDocument();
    });

    it("does NOT render the SL viewer recipe post-confirmation", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: "2026-05-01T10:00:00Z",
          })}
          role="seller"
        />,
      );
      expect(screen.queryByText(/about land/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/sell land/i)).not.toBeInTheDocument();
    });
  });
});
