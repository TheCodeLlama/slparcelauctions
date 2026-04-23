import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { ExpiredStateCard } from "./ExpiredStateCard";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("ExpiredStateCard", () => {
  describe("pre-fund payment timeout (fundedAt == null)", () => {
    it("renders payment-timeout copy for seller", () => {
      renderWithProviders(
        <ExpiredStateCard
          escrow={fakeEscrow({
            state: "EXPIRED",
            fundedAt: null,
            expiredAt: "2026-05-03T12:00:00Z",
          })}
          role="seller"
        />,
      );
      expect(
        screen.getByText(
          /winner didn't pay by the deadline/i,
        ),
      ).toBeInTheDocument();
    });

    it("renders blunt payment-timeout copy for winner", () => {
      renderWithProviders(
        <ExpiredStateCard
          escrow={fakeEscrow({
            state: "EXPIRED",
            fundedAt: null,
            expiredAt: "2026-05-03T12:00:00Z",
          })}
          role="winner"
        />,
      );
      expect(
        screen.getByText(/you didn't pay by the deadline/i),
      ).toBeInTheDocument();
    });

    it("does not mention a refund for pre-fund expiry", () => {
      renderWithProviders(
        <ExpiredStateCard
          escrow={fakeEscrow({
            state: "EXPIRED",
            fundedAt: null,
            expiredAt: "2026-05-03T12:00:00Z",
          })}
          role="winner"
        />,
      );
      expect(screen.queryByText(/refund/i)).not.toBeInTheDocument();
    });
  });

  describe("post-fund transfer timeout (fundedAt != null)", () => {
    it("renders transfer-timeout + refund queued copy for seller", () => {
      renderWithProviders(
        <ExpiredStateCard
          escrow={fakeEscrow({
            state: "EXPIRED",
            fundedAt: "2026-04-30T12:00:00Z",
            expiredAt: "2026-05-03T12:00:00Z",
            finalBidAmount: 5000,
          })}
          role="seller"
        />,
      );
      expect(
        screen.getByText(/transfer wasn't completed/i),
      ).toBeInTheDocument();
      expect(
        screen.getByText(/refund of l\$\s*5,?000/i),
      ).toBeInTheDocument();
      expect(screen.getByText(/queued to the winner/i)).toBeInTheDocument();
    });

    it("renders transfer-timeout + refund queued copy for winner", () => {
      renderWithProviders(
        <ExpiredStateCard
          escrow={fakeEscrow({
            state: "EXPIRED",
            fundedAt: "2026-04-30T12:00:00Z",
            expiredAt: "2026-05-03T12:00:00Z",
            finalBidAmount: 5000,
          })}
          role="winner"
        />,
      );
      expect(
        screen.getByText(/seller didn't complete the transfer/i),
      ).toBeInTheDocument();
      expect(
        screen.getByText(/your l\$\s*5,?000 refund has been queued/i),
      ).toBeInTheDocument();
    });
  });

  it("does not render a dispute link", () => {
    renderWithProviders(
      <ExpiredStateCard
        escrow={fakeEscrow({
          state: "EXPIRED",
          fundedAt: null,
          expiredAt: "2026-05-03T12:00:00Z",
        })}
        role="seller"
      />,
    );
    expect(
      screen.queryByRole("link", { name: /file a dispute/i }),
    ).not.toBeInTheDocument();
  });
});
