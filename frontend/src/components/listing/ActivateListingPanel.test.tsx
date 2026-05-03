import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type { WalletView } from "@/types/wallet";
import { ActivateListingPanel } from "./ActivateListingPanel";

vi.mock("@/lib/wallet/use-wallet", async () => {
  const actual = await vi.importActual<
    typeof import("@/lib/wallet/use-wallet")
  >("@/lib/wallet/use-wallet");
  return {
    ...actual,
    useWallet: vi.fn(),
  };
});

vi.mock("@/hooks/useListingFeeConfig", () => ({
  useListingFeeConfig: vi.fn(),
  LISTING_FEE_CONFIG_KEY: ["config", "listing-fee"],
}));

import { useWallet } from "@/lib/wallet/use-wallet";
import { useListingFeeConfig } from "@/hooks/useListingFeeConfig";

function walletView(overrides: Partial<WalletView> = {}): WalletView {
  return {
    balance: 1000,
    reserved: 0,
    available: 1000,
    penaltyOwed: 0,
    queuedForWithdrawal: 0,
    termsAccepted: true,
    termsVersion: "v1.0",
    termsAcceptedAt: "2026-04-17T00:00:00Z",
    recentLedger: [],
    ...overrides,
  };
}

function mockHooks(opts: {
  feeLoading?: boolean;
  fee?: number;
  walletLoading?: boolean;
  walletFetching?: boolean;
  wallet?: WalletView;
  refetch?: () => void;
}) {
  vi.mocked(useListingFeeConfig).mockReturnValue({
    isLoading: opts.feeLoading ?? false,
    data: opts.feeLoading
      ? undefined
      : { amountLindens: opts.fee ?? 100 },
  } as unknown as ReturnType<typeof useListingFeeConfig>);
  vi.mocked(useWallet).mockReturnValue({
    isLoading: opts.walletLoading ?? false,
    isFetching: opts.walletFetching ?? false,
    data: opts.walletLoading ? undefined : opts.wallet ?? walletView(),
    refetch: opts.refetch ?? vi.fn(),
  } as unknown as ReturnType<typeof useWallet>);
}

describe("ActivateListingPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders a loading spinner while fee or wallet is pending", () => {
    mockHooks({ feeLoading: true, walletLoading: true });
    renderWithProviders(<ActivateListingPanel auctionId={42} />);
    expect(screen.getByText(/Loading fee details/i)).toBeInTheDocument();
  });

  it("shows the wallet-terms gate with an inline Accept button when terms are not accepted", async () => {
    mockHooks({
      wallet: walletView({ termsAccepted: false, termsAcceptedAt: null }),
    });
    renderWithProviders(<ActivateListingPanel auctionId={42} />);
    expect(
      screen.getByRole("heading", { name: /Accept wallet terms first/i }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Activate Listing/i }),
    ).not.toBeInTheDocument();
    // Clicking the gate button opens the WalletTermsModal in-place — no
    // navigation to /wallet.
    await userEvent.click(
      screen.getByRole("button", { name: /Accept wallet terms/i }),
    );
    expect(
      screen.getByRole("heading", { name: /SLPA Wallet Terms of Use/i }),
    ).toBeInTheDocument();
  });

  it("shows the penalty gate when penalty is owed", () => {
    mockHooks({
      wallet: walletView({ penaltyOwed: 250 }),
    });
    renderWithProviders(<ActivateListingPanel auctionId={42} />);
    expect(
      screen.getByRole("heading", {
        name: /Pay penalty before activating/i,
      }),
    ).toBeInTheDocument();
    expect(screen.getByText(/L\$250/)).toBeInTheDocument();
  });

  it("shows the top-up state when available balance is below the fee", async () => {
    const refetch = vi.fn();
    mockHooks({
      fee: 100,
      wallet: walletView({ balance: 60, available: 60 }),
      refetch,
    });
    renderWithProviders(<ActivateListingPanel auctionId={42} />);
    expect(
      screen.getByRole("heading", { name: /Top up your wallet/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(/L\$40/)).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: /Refresh balance/i }),
    );
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it("renders the activate button when ready and posts the listing-fee debit on click", async () => {
    let posted = false;
    server.use(
      http.post("*/api/v1/me/auctions/42/pay-listing-fee", async () => {
        posted = true;
        return HttpResponse.json({
          newBalance: 900,
          newAvailable: 900,
          auctionStatus: "DRAFT_PAID",
        });
      }),
    );
    mockHooks({
      fee: 100,
      wallet: walletView({ balance: 1000, available: 1000 }),
    });
    renderWithProviders(<ActivateListingPanel auctionId={42} />);

    const button = screen.getByRole("button", { name: /Activate Listing/i });
    await userEvent.click(button);
    await waitFor(() => expect(posted).toBe(true));
  });

  it("surfaces a generic 500 error inline", async () => {
    server.use(
      http.post("*/api/v1/me/auctions/42/pay-listing-fee", () =>
        HttpResponse.json(
          {
            status: 500,
            title: "Internal Server Error",
            detail: "Database is down.",
          },
          { status: 500 },
        ),
      ),
    );
    mockHooks({
      fee: 100,
      wallet: walletView({ balance: 1000, available: 1000 }),
    });
    renderWithProviders(<ActivateListingPanel auctionId={42} />);
    await userEvent.click(
      screen.getByRole("button", { name: /Activate Listing/i }),
    );
    expect(
      await screen.findByText(/Database is down/i),
    ).toBeInTheDocument();
  });

  it("does NOT surface inline copy on a coded error (lets fresh wallet data re-render the state)", async () => {
    server.use(
      http.post("*/api/v1/me/auctions/42/pay-listing-fee", () =>
        HttpResponse.json(
          {
            status: 422,
            title: "Penalty outstanding",
            detail: "Pay your penalty first.",
            code: "PENALTY_OUTSTANDING",
          },
          { status: 422 },
        ),
      ),
    );
    mockHooks({
      fee: 100,
      wallet: walletView({ balance: 1000, available: 1000 }),
    });
    renderWithProviders(<ActivateListingPanel auctionId={42} />);
    await userEvent.click(
      screen.getByRole("button", { name: /Activate Listing/i }),
    );
    // No inline FormError appears — the coded branch leans on the
    // wallet refetch for state, not a freeform message.
    await waitFor(() =>
      expect(
        screen.queryByText(/Pay your penalty first/i),
      ).not.toBeInTheDocument(),
    );
  });
});
