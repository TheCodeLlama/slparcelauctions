import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { GroupWalletBalanceCard } from "./GroupWalletBalanceCard";

describe("GroupWalletBalanceCard", () => {
  it("renders available, balance amounts", () => {
    renderWithProviders(
      <GroupWalletBalanceCard balance={12500} reserved={0} available={12500} />,
    );
    expect(screen.getByTestId("balance-available")).toHaveTextContent("L$12,500");
    expect(screen.getByTestId("balance-total")).toHaveTextContent("L$12,500");
  });

  it("renders reserved row as L$0 when reserved is zero", () => {
    // Always-render the reserved row so the relationship between
    // available / balance / reserved is obvious -- the prior "hide
    // when zero" guard made the card read as Balance-only at a glance.
    renderWithProviders(
      <GroupWalletBalanceCard balance={1000} reserved={0} available={1000} />,
    );
    expect(screen.getByTestId("balance-reserved")).toHaveTextContent("L$0");
  });

  it("shows reserved row when reserved is nonzero", () => {
    renderWithProviders(
      <GroupWalletBalanceCard balance={1000} reserved={200} available={800} />,
    );
    expect(screen.getByTestId("balance-reserved")).toHaveTextContent("L$200");
  });

  it("shows withdraw button when canWithdraw and onWithdraw provided", () => {
    const onWithdraw = vi.fn();
    renderWithProviders(
      <GroupWalletBalanceCard
        balance={500}
        reserved={0}
        available={500}
        canWithdraw
        onWithdraw={onWithdraw}
      />,
    );
    expect(screen.getByTestId("withdraw-button")).toBeInTheDocument();
  });

  it("does not show withdraw button when canWithdraw is false", () => {
    renderWithProviders(
      <GroupWalletBalanceCard
        balance={500}
        reserved={0}
        available={500}
        canWithdraw={false}
        onWithdraw={vi.fn()}
      />,
    );
    expect(screen.queryByTestId("withdraw-button")).not.toBeInTheDocument();
  });

  it("disables withdraw button when available is zero", () => {
    renderWithProviders(
      <GroupWalletBalanceCard
        balance={0}
        reserved={0}
        available={0}
        canWithdraw
        onWithdraw={vi.fn()}
      />,
    );
    expect(screen.getByTestId("withdraw-button")).toBeDisabled();
  });

  it("calls onWithdraw when button is clicked", async () => {
    const onWithdraw = vi.fn();
    renderWithProviders(
      <GroupWalletBalanceCard
        balance={500}
        reserved={0}
        available={500}
        canWithdraw
        onWithdraw={onWithdraw}
      />,
    );
    await userEvent.click(screen.getByTestId("withdraw-button"));
    expect(onWithdraw).toHaveBeenCalledOnce();
  });
});
