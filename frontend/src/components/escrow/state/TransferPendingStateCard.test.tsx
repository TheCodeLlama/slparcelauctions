import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  renderWithProviders,
  screen,
  fireEvent,
  waitFor,
} from "@/test/render";
import { TransferPendingStateCard } from "./TransferPendingStateCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

const verifySellToMutate = vi.fn();
const verifyTransferMutate = vi.fn();
const requestReviewMutate = vi.fn();

vi.mock("@/hooks/useEscrowManualActions", () => ({
  useVerifySellTo: () => ({ mutate: verifySellToMutate, isPending: false }),
  useVerifyTransfer: () => ({ mutate: verifyTransferMutate, isPending: false }),
  useRequestManualReview: () => ({
    mutate: requestReviewMutate,
    isPending: false,
  }),
}));

beforeEach(() => {
  verifySellToMutate.mockReset();
  verifyTransferMutate.mockReset();
  requestReviewMutate.mockReset();
});

describe("TransferPendingStateCard", () => {
  describe("Set Sell To (sellToConfirmedAt == null), seller", () => {
    beforeEach(() => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date("2026-05-01T12:00:00Z"));
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    function renderSeller(over = {}) {
      return renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            sellToConfirmedAt: null,
            transferConfirmedAt: null,
            transferDeadline: "2026-05-03T12:00:00Z",
            ...over,
          })}
          role="seller"
        />,
      );
    }

    it("renders the numbered SL-viewer Set Sell To recipe", () => {
      renderSeller();
      expect(screen.getByText(/about land/i)).toBeInTheDocument();
      expect(screen.getByText(/sell land/i)).toBeInTheDocument();
      expect(screen.getByText(/l\$\s*0/i)).toBeInTheDocument();
      expect(screen.getByText(/confirm the sale/i)).toBeInTheDocument();
    });

    it("renders the deadline badge", () => {
      const { container } = renderSeller();
      expect(
        container.querySelector("[data-urgency]"),
      ).toBeInTheDocument();
    });

    it("renders the winner's SL avatar name with a copy button", async () => {
      vi.useRealTimers();
      const writeTextMock = vi
        .fn<(text: string) => Promise<void>>()
        .mockResolvedValue(undefined);
      Object.defineProperty(navigator, "clipboard", {
        value: { writeText: writeTextMock },
        writable: true,
        configurable: true,
      });
      renderSeller({ winnerSlAvatarName: "Alice Bidder Resident" });
      expect(screen.getByTestId("winner-sl-avatar-name")).toHaveTextContent(
        "Alice Bidder Resident",
      );
      const copyBtn = screen.getByTestId("copy-winner-sl-avatar-name-btn");
      fireEvent.click(copyBtn);
      await waitFor(() => {
        expect(writeTextMock).toHaveBeenCalledWith("Alice Bidder Resident");
      });
    });

    it("falls back to generic copy when winnerSlAvatarName is null", () => {
      renderSeller({ winnerSlAvatarName: null });
      expect(
        screen.queryByTestId("winner-sl-avatar-name"),
      ).not.toBeInTheDocument();
      expect(screen.getByText(/winner's avatar name/i)).toBeInTheDocument();
    });

    it("renders the parcel SLURL map link from parcelMapUrl", () => {
      renderSeller({ parcelMapUrl: "https://maps.secondlife.com/x/1/2/3" });
      const link = screen.getByRole("link", { name: /open parcel in/i });
      expect(link).toHaveAttribute(
        "href",
        "https://maps.secondlife.com/x/1/2/3",
      );
    });

    it("Verify Sell To button shows '3 of 3 attempts' and the 30-min bot warning", () => {
      renderSeller({ sellToVerifyAttemptsRemaining: 3 });
      expect(
        screen.getByRole("button", { name: /verify sell to/i }),
      ).toBeEnabled();
      expect(screen.getByText(/3 of 3/i)).toBeInTheDocument();
      expect(screen.getByText(/every 30 min/i)).toBeInTheDocument();
    });

    it("clicking Verify Sell To invokes the verifySellTo hook", () => {
      renderSeller();
      fireEvent.click(
        screen.getByRole("button", { name: /verify sell to/i }),
      );
      expect(verifySellToMutate).toHaveBeenCalledTimes(1);
    });

    it("disables Verify Sell To at 0 attempts remaining and surfaces the review link", () => {
      renderSeller({ sellToVerifyAttemptsRemaining: 0 });
      expect(
        screen.getByRole("button", { name: /verify sell to/i }),
      ).toBeDisabled();
      expect(
        screen.getByRole("button", { name: /request manual review/i }),
      ).toBeInTheDocument();
    });

    it("shows an inline error when sellToLastResult is set", () => {
      renderSeller({ sellToLastResult: "WRONG_BUYER" });
      expect(screen.getByText(/WRONG_BUYER/)).toBeInTheDocument();
    });

    it("Request manual review invokes the requestManualReview hook", () => {
      renderSeller();
      fireEvent.click(
        screen.getByRole("button", { name: /request manual review/i }),
      );
      expect(requestReviewMutate).toHaveBeenCalledTimes(1);
    });

    it("renders BOTH the manual-review affordance AND the File a dispute link", () => {
      renderSeller({ auctionPublicId: "auction-set-sell-to-seller" });
      expect(
        screen.getByRole("button", { name: /request manual review/i }),
      ).toBeInTheDocument();
      const dispute = screen.getByRole("link", {
        name: /file a dispute/i,
      });
      expect(dispute).toHaveAttribute(
        "href",
        "/auction/auction-set-sell-to-seller/escrow/dispute",
      );
    });
  });

  describe("Set Sell To (sellToConfirmedAt == null), winner", () => {
    function renderWinnerWaiting(over = {}) {
      return renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            sellToConfirmedAt: null,
            transferConfirmedAt: null,
            parcelMapUrl: "https://maps.secondlife.com/x/4/5/6",
            ...over,
          })}
          role="winner"
        />,
      );
    }

    it("renders waiting copy + the parcel SLURL", () => {
      renderWinnerWaiting();
      expect(
        screen.getByText(/waiting for the seller/i),
      ).toBeInTheDocument();
      const link = screen.getByRole("link", { name: /open parcel in/i });
      expect(link).toHaveAttribute(
        "href",
        "https://maps.secondlife.com/x/4/5/6",
      );
    });

    it("renders the File a dispute link", () => {
      renderWinnerWaiting({ auctionPublicId: "auction-set-sell-to-winner" });
      const dispute = screen.getByRole("link", {
        name: /file a dispute/i,
      });
      expect(dispute).toHaveAttribute(
        "href",
        "/auction/auction-set-sell-to-winner/escrow/dispute",
      );
    });
  });

  describe("Buy Parcel (sellToConfirmedAt set, transferConfirmedAt == null), winner", () => {
    function renderWinner(over = {}) {
      return renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            sellToConfirmedAt: "2026-05-01T09:00:00Z",
            transferConfirmedAt: null,
            parcelMapUrl: "https://maps.secondlife.com/x/7/8/9",
            ...over,
          })}
          role="winner"
        />,
      );
    }

    it("renders buy-now guidance, only-if-L$0 warning, and the SLURL", () => {
      renderWinner();
      expect(screen.getByText(/buy it now/i)).toBeInTheDocument();
      expect(screen.getByText(/only if/i)).toBeInTheDocument();
      expect(screen.getByText(/l\$\s*0/i)).toBeInTheDocument();
      const link = screen.getByRole("link", { name: /open parcel in/i });
      expect(link).toHaveAttribute(
        "href",
        "https://maps.secondlife.com/x/7/8/9",
      );
    });

    it("Verify purchase button shows attempts and invokes verifyTransfer", () => {
      renderWinner({ buyVerifyBuyerAttemptsRemaining: 3 });
      expect(screen.getByText(/3 of 3/i)).toBeInTheDocument();
      const btn = screen.getByRole("button", { name: /verify purchase/i });
      fireEvent.click(btn);
      expect(verifyTransferMutate).toHaveBeenCalledTimes(1);
    });

    it("disables Verify purchase at 0 attempts and surfaces the review link", () => {
      renderWinner({ buyVerifyBuyerAttemptsRemaining: 0 });
      expect(
        screen.getByRole("button", { name: /verify purchase/i }),
      ).toBeDisabled();
      expect(
        screen.getByRole("button", { name: /request manual review/i }),
      ).toBeInTheDocument();
    });

    it("renders BOTH the manual-review affordance AND the File a dispute link", () => {
      renderWinner({ auctionPublicId: "auction-buy-parcel-winner" });
      expect(
        screen.getByRole("button", { name: /request manual review/i }),
      ).toBeInTheDocument();
      const dispute = screen.getByRole("link", {
        name: /file a dispute/i,
      });
      expect(dispute).toHaveAttribute(
        "href",
        "/auction/auction-buy-parcel-winner/escrow/dispute",
      );
    });
  });

  describe("Buy Parcel (sellToConfirmedAt set, transferConfirmedAt == null), seller", () => {
    function renderSellerWaiting(over = {}) {
      return renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            sellToConfirmedAt: "2026-05-01T09:00:00Z",
            transferConfirmedAt: null,
            buyVerifySellerAttemptsRemaining: 2,
            ...over,
          })}
          role="seller"
        />,
      );
    }

    it("renders waiting + Verify purchase (seller attempts) + request review", () => {
      renderSellerWaiting();
      expect(
        screen.getByText(/waiting for the winner/i),
      ).toBeInTheDocument();
      expect(screen.getByText(/2 of 3/i)).toBeInTheDocument();
      const btn = screen.getByRole("button", { name: /verify purchase/i });
      fireEvent.click(btn);
      expect(verifyTransferMutate).toHaveBeenCalledTimes(1);
      expect(
        screen.getByRole("button", { name: /request manual review/i }),
      ).toBeInTheDocument();
    });

    it("renders BOTH the manual-review affordance AND the File a dispute link", () => {
      renderSellerWaiting({ auctionPublicId: "auction-buy-parcel-seller" });
      expect(
        screen.getByRole("button", { name: /request manual review/i }),
      ).toBeInTheDocument();
      const dispute = screen.getByRole("link", {
        name: /file a dispute/i,
      });
      expect(dispute).toHaveAttribute(
        "href",
        "/auction/auction-buy-parcel-seller/escrow/dispute",
      );
    });
  });

  describe("post-confirmation (payout pending) — unchanged, role-neutral", () => {
    it.each(["seller", "winner"] as const)(
      "renders the payout-pending copy for role=%s",
      (role) => {
        renderWithProviders(
          <TransferPendingStateCard
            escrow={fakeEscrow({
              state: "TRANSFER_PENDING",
              fundedAt: "2026-04-30T12:00:00Z",
              sellToConfirmedAt: "2026-05-01T09:00:00Z",
              transferConfirmedAt: "2026-05-01T10:00:00Z",
              transferDeadline: "2026-05-03T12:00:00Z",
            })}
            role={role}
          />,
        );
        expect(
          screen.getByText(/ownership transferred to the winner/i),
        ).toBeInTheDocument();
        expect(
          screen.getByText(/finalizing the transaction/i),
        ).toBeInTheDocument();
      },
    );

    it("does NOT render the SL viewer recipe post-confirmation", () => {
      renderWithProviders(
        <TransferPendingStateCard
          escrow={fakeEscrow({
            state: "TRANSFER_PENDING",
            fundedAt: "2026-04-30T12:00:00Z",
            sellToConfirmedAt: "2026-05-01T09:00:00Z",
            transferConfirmedAt: "2026-05-01T10:00:00Z",
          })}
          role="seller"
        />,
      );
      expect(screen.queryByText(/about land/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/sell land/i)).not.toBeInTheDocument();
    });

    it.each(["seller", "winner"] as const)(
      "renders NEITHER the manual-review affordance NOR the File a dispute link post-confirmation for role=%s",
      (role) => {
        renderWithProviders(
          <TransferPendingStateCard
            escrow={fakeEscrow({
              state: "TRANSFER_PENDING",
              fundedAt: "2026-04-30T12:00:00Z",
              sellToConfirmedAt: "2026-05-01T09:00:00Z",
              transferConfirmedAt: "2026-05-01T10:00:00Z",
              auctionPublicId: "auction-payout-pending",
            })}
            role={role}
          />,
        );
        expect(
          screen.queryByRole("button", { name: /request manual review/i }),
        ).not.toBeInTheDocument();
        expect(
          screen.queryByRole("link", { name: /file a dispute/i }),
        ).not.toBeInTheDocument();
      },
    );
  });
});
