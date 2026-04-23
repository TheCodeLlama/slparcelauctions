import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
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
            counterparty: {
              userId: 42,
              displayName: "Kira Swansong",
              slAvatarName: "kira.swansong",
              slAvatarUuid: "a0b1c2d3-0000-4000-8000-000000000001",
            },
          })}
          role="seller"
        />,
      );
      expect(screen.getByText(/about land/i)).toBeInTheDocument();
      expect(screen.getByText(/sell land/i)).toBeInTheDocument();
      // slAvatarName appears in the recipe step (in-world avatar key).
      expect(screen.getByText("kira.swansong")).toBeInTheDocument();
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

    it("copies the winner slAvatarName to clipboard on button click", async () => {
      vi.useRealTimers();
      const user = userEvent.setup();
      const writeText = vi.fn().mockResolvedValue(undefined);
      // `navigator.clipboard` is read-only in JSDOM; redefine via
      // defineProperty so writes succeed for the duration of the test.
      Object.defineProperty(navigator, "clipboard", {
        configurable: true,
        value: { writeText },
      });

      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
            counterparty: {
              userId: 42,
              displayName: "Kira Swansong",
              slAvatarName: "kira.swansong",
              slAvatarUuid: "a0b1c2d3-0000-4000-8000-000000000001",
            },
          })}
          role="seller"
        />,
      );
      const btn = screen.getByRole("button", { name: /copy winner name/i });
      await user.click(btn);
      expect(writeText).toHaveBeenCalledWith("kira.swansong");
    });

    it("renders a dispute link", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            auctionId: 42,
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
          })}
          role="seller"
        />,
      );
      const link = screen.getByRole("link", { name: /file a dispute/i });
      expect(link).toHaveAttribute("href", "/auction/42/escrow/dispute");
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
      expect(screen.getByText(/message seller/i)).toBeInTheDocument();
      expect(screen.getByText(/> 48 hours/i)).toBeInTheDocument();
    });

    it("renders a disabled 'Message {name}' button (inert placeholder)", () => {
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
      const btn = screen.getByRole("button", { name: /message kira swansong/i });
      expect(btn).toBeDisabled();
    });

    it("renders deadline badge + dispute link", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            auctionId: 42,
            fundedAt: "2026-04-30T12:00:00Z",
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
          })}
          role="winner"
        />,
      );
      expect(screen.getByText(/left$/i)).toBeInTheDocument();
      const link = screen.getByRole("link", { name: /file a dispute/i });
      expect(link).toHaveAttribute("href", "/auction/42/escrow/dispute");
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
