import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { PendingStateCard } from "./PendingStateCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("PendingStateCard", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-05-01T12:00:00Z"));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  describe("seller view", () => {
    it("renders 'Awaiting payment' headline with counterparty display name", () => {
      renderWithProviders(
        <PendingStateCard
          escrow={fakeEscrow({
            state: "ESCROW_PENDING",
            paymentDeadline: "2026-05-03T12:00:00Z",
          })}
          role="seller"
        />,
      );
      expect(
        screen.getByText(/awaiting payment from the winner/i),
      ).toBeInTheDocument();
    });

    it("renders a deadline badge", () => {
      renderWithProviders(
        <PendingStateCard
          escrow={fakeEscrow({
            state: "ESCROW_PENDING",
            paymentDeadline: "2026-05-03T12:00:00Z",
          })}
          role="seller"
        />,
      );
      expect(screen.getByText(/left$/i)).toBeInTheDocument();
    });

    it("renders a dispute link with correct href", () => {
      renderWithProviders(
        <PendingStateCard
          escrow={fakeEscrow({ state: "ESCROW_PENDING", auctionPublicId: "00000000-0000-0000-0000-00000000002a" })}
          role="seller"
        />,
      );
      const link = screen.getByRole("link", { name: /file a dispute/i });
      expect(link).toHaveAttribute("href", "/auction/00000000-0000-0000-0000-00000000002a/escrow/dispute");
    });

    it("does not render 'Find a terminal' or 'Pay L$' copy", () => {
      renderWithProviders(
        <PendingStateCard
          escrow={fakeEscrow({ state: "ESCROW_PENDING" })}
          role="seller"
        />,
      );
      expect(screen.queryByText(/find a terminal/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/pay l\$/i)).not.toBeInTheDocument();
    });
  });

  describe("winner view", () => {
    it("renders 'Pay L$ {finalBidAmount}' headline", () => {
      renderWithProviders(
        <PendingStateCard
          escrow={fakeEscrow({
            state: "ESCROW_PENDING",
            finalBidAmount: 5000,
          })}
          role="winner"
        />,
      );
      expect(screen.getByText(/pay l\$\s*5,?000/i)).toBeInTheDocument();
    });

    it("renders terminal instructions body copy", () => {
      renderWithProviders(
        <PendingStateCard
          escrow={fakeEscrow({ state: "ESCROW_PENDING" })}
          role="winner"
        />,
      );
      expect(
        screen.getByText(/slpa terminal in-world/i),
      ).toBeInTheDocument();
    });

    it("renders a disabled 'Find a terminal' button (inert placeholder)", () => {
      renderWithProviders(
        <PendingStateCard
          escrow={fakeEscrow({ state: "ESCROW_PENDING" })}
          role="winner"
        />,
      );
      const btn = screen.getByRole("button", { name: /find a terminal/i });
      expect(btn).toBeDisabled();
    });

    it("renders a deadline badge", () => {
      renderWithProviders(
        <PendingStateCard
          escrow={fakeEscrow({
            state: "ESCROW_PENDING",
            paymentDeadline: "2026-05-03T12:00:00Z",
          })}
          role="winner"
        />,
      );
      expect(screen.getByText(/left$/i)).toBeInTheDocument();
    });

    it("renders a dispute link with correct href", () => {
      renderWithProviders(
        <PendingStateCard
          escrow={fakeEscrow({ state: "ESCROW_PENDING", auctionPublicId: "00000000-0000-0000-0000-00000000002a" })}
          role="winner"
        />,
      );
      const link = screen.getByRole("link", { name: /file a dispute/i });
      expect(link).toHaveAttribute("href", "/auction/00000000-0000-0000-0000-00000000002a/escrow/dispute");
    });
  });
});
