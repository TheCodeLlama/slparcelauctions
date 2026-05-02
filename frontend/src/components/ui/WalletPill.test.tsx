import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { WalletPill } from "./WalletPill";

vi.mock("@/lib/wallet/use-wallet", () => ({
  useWallet: vi.fn(),
}));
vi.mock("@/lib/wallet/use-wallet-ws", () => ({
  useWalletWsSubscription: vi.fn(),
}));
vi.mock("@/lib/user", () => ({
  useCurrentUser: vi.fn(),
}));

import { useWallet } from "@/lib/wallet/use-wallet";
import { useCurrentUser } from "@/lib/user";

describe("WalletPill", () => {
  beforeEach(() => {
    vi.mocked(useCurrentUser).mockReturnValue({
      data: { id: 1, verified: true },
    } as unknown as ReturnType<typeof useCurrentUser>);
  });

  it("renders L$ available amount with tabular-nums", () => {
    vi.mocked(useWallet).mockReturnValue({
      data: { balance: 12000, reserved: 2000, available: 10000, penaltyOwed: 0, queuedForWithdrawal: 0 },
    } as unknown as ReturnType<typeof useWallet>);

    render(<WalletPill />);

    expect(screen.getAllByText(/10,000/).length).toBeGreaterThan(0);
    expect(screen.getByLabelText(/wallet/i)).toHaveAttribute("href", "/wallet");
  });

  it("returns null for unverified users", () => {
    vi.mocked(useCurrentUser).mockReturnValue({
      data: { id: 1, verified: false },
    } as unknown as ReturnType<typeof useCurrentUser>);
    vi.mocked(useWallet).mockReturnValue({ data: undefined } as unknown as ReturnType<typeof useWallet>);

    const { container } = render(<WalletPill />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders L$ 0 while wallet is loading", () => {
    vi.mocked(useWallet).mockReturnValue({ data: undefined } as unknown as ReturnType<typeof useWallet>);

    render(<WalletPill />);
    expect(screen.getAllByText(/L\$\s*0\b/).length).toBeGreaterThan(0);
  });
});
