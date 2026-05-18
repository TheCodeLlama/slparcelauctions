import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import type { WalletView } from "@/types/wallet";
import { WALLET_TERMS_VERSION } from "@/components/wallet/WalletTermsModal";
import { termsBannerDismissalKey } from "@/lib/wallet/terms-banner-dismissed";

vi.mock("@/lib/user", () => ({
  useCurrentUser: vi.fn(),
}));

vi.mock("@/lib/wallet/use-wallet", async () => {
  const actual = await vi.importActual<
    typeof import("@/lib/wallet/use-wallet")
  >("@/lib/wallet/use-wallet");
  return { ...actual, useWallet: vi.fn() };
});

import { useCurrentUser } from "@/lib/user";
import { useWallet } from "@/lib/wallet/use-wallet";
import { WalletTermsBanner } from "./WalletTermsBanner";

function walletView(overrides: Partial<WalletView> = {}): WalletView {
  return {
    balance: 0,
    reserved: 0,
    available: 0,
    penaltyOwed: 0,
    queuedForWithdrawal: 0,
    termsAccepted: false,
    termsVersion: null,
    termsAcceptedAt: null,
    recentLedger: [],
    ...overrides,
  };
}

function setUser(opts: { verified: boolean } | null) {
  vi.mocked(useCurrentUser).mockReturnValue(
    opts === null
      ? ({ data: undefined } as unknown as ReturnType<typeof useCurrentUser>)
      : ({
          data: { verified: opts.verified },
        } as unknown as ReturnType<typeof useCurrentUser>),
  );
}

function setWallet(data: WalletView | undefined) {
  vi.mocked(useWallet).mockReturnValue({
    data,
  } as unknown as ReturnType<typeof useWallet>);
}

const BANNER = /Accept the SLParcels Wallet Terms of Use/i;

describe("WalletTermsBanner", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
  });

  it("shows for a verified user who has not accepted terms", () => {
    setUser({ verified: true });
    setWallet(walletView({ termsAccepted: false }));
    renderWithProviders(<WalletTermsBanner />);
    expect(screen.getByText(BANNER)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Accept Wallet Terms/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Don't show again/i }),
    ).toBeInTheDocument();
  });

  it("is hidden for guests / unauthenticated", () => {
    setUser(null);
    setWallet(undefined);
    renderWithProviders(<WalletTermsBanner />);
    expect(screen.queryByText(BANNER)).not.toBeInTheDocument();
  });

  it("is hidden for an authenticated but unverified user", () => {
    setUser({ verified: false });
    setWallet(undefined);
    renderWithProviders(<WalletTermsBanner />);
    expect(screen.queryByText(BANNER)).not.toBeInTheDocument();
  });

  it("renders nothing while the wallet query is loading", () => {
    setUser({ verified: true });
    setWallet(undefined);
    renderWithProviders(<WalletTermsBanner />);
    expect(screen.queryByText(BANNER)).not.toBeInTheDocument();
  });

  it("is hidden once terms are accepted", () => {
    setUser({ verified: true });
    setWallet(walletView({ termsAccepted: true }));
    renderWithProviders(<WalletTermsBanner />);
    expect(screen.queryByText(BANNER)).not.toBeInTheDocument();
  });

  it("is hidden when dismissed in localStorage for the current version", async () => {
    window.localStorage.setItem(
      termsBannerDismissalKey(WALLET_TERMS_VERSION),
      "1",
    );
    setUser({ verified: true });
    setWallet(walletView({ termsAccepted: false }));
    renderWithProviders(<WalletTermsBanner />);
    await waitFor(() =>
      expect(screen.queryByText(BANNER)).not.toBeInTheDocument(),
    );
  });

  it("opens the WalletTermsModal on Accept Wallet Terms", async () => {
    setUser({ verified: true });
    setWallet(walletView({ termsAccepted: false }));
    renderWithProviders(<WalletTermsBanner />);
    await userEvent.click(
      screen.getByRole("button", { name: /Accept Wallet Terms/i }),
    );
    expect(
      screen.getByRole("heading", { name: /SLParcels Wallet Terms of Use/i }),
    ).toBeInTheDocument();
  });

  it("Don't show again writes localStorage and hides the banner", async () => {
    setUser({ verified: true });
    setWallet(walletView({ termsAccepted: false }));
    renderWithProviders(<WalletTermsBanner />);
    await userEvent.click(
      screen.getByRole("button", { name: /Don't show again/i }),
    );
    expect(
      window.localStorage.getItem(
        termsBannerDismissalKey(WALLET_TERMS_VERSION),
      ),
    ).toBe("1");
    await waitFor(() =>
      expect(screen.queryByText(BANNER)).not.toBeInTheDocument(),
    );
  });
});
