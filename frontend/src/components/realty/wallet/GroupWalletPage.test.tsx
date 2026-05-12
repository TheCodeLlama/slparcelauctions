import { describe, expect, it } from "vitest";
import { renderWithProviders, screen, waitFor, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { http, HttpResponse } from "msw";
import { realtyGroupWalletHandlers } from "@/test/msw/handlers";
import { GroupWalletPage } from "./GroupWalletPage";

const GROUP_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

describe("GroupWalletPage", () => {
  it("renders the balance card and ledger section after loading", async () => {
    server.use(
      realtyGroupWalletHandlers.walletSuccess(GROUP_ID, {
        balance: 5000,
        reserved: 0,
        available: 5000,
        recentLedger: [],
      }),
    );
    renderWithProviders(<GroupWalletPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("group-wallet-page")).toBeInTheDocument(),
    );
    expect(screen.getByTestId("group-wallet-balance-card")).toBeInTheDocument();
    expect(screen.getByText(/Transaction history/i)).toBeInTheDocument();
  });

  it("renders permission-denied notice on 403", async () => {
    server.use(
      http.get(
        `*/api/v1/realty/groups/${GROUP_ID}/wallet`,
        () =>
          HttpResponse.json(
            {
              status: 403,
              code: "INSUFFICIENT_GROUP_PERMISSION",
              title: "Forbidden",
            },
            { status: 403 },
          ),
      ),
    );
    renderWithProviders(<GroupWalletPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("permission-denied")).toBeInTheDocument(),
    );
    expect(screen.getByText(/Access restricted/i)).toBeInTheDocument();
  });

  it("opens the withdraw modal when Withdraw is clicked", async () => {
    server.use(
      realtyGroupWalletHandlers.walletSuccess(GROUP_ID, {
        balance: 3000,
        reserved: 0,
        available: 3000,
        recentLedger: [],
      }),
    );
    renderWithProviders(<GroupWalletPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("withdraw-button")).toBeInTheDocument(),
    );
    await userEvent.click(screen.getByTestId("withdraw-button"));
    expect(
      screen.getByRole("dialog", { name: /Withdraw from Group Wallet/i }),
    ).toBeInTheDocument();
  });

  it("shows the leader terms banner when leaderTermsAcceptedAt is null in the wallet DTO", async () => {
    server.use(
      realtyGroupWalletHandlers.walletSuccess(GROUP_ID, {
        balance: 1000,
        reserved: 0,
        available: 1000,
        recentLedger: [],
        // Extended field — banner activates when present and null
        leaderTermsAcceptedAt: null,
      }),
    );
    renderWithProviders(<GroupWalletPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId("leader-terms-block-banner"),
      ).toBeInTheDocument(),
    );
  });

  it("does not show leader terms banner when leaderTermsAcceptedAt is a timestamp", async () => {
    server.use(
      realtyGroupWalletHandlers.walletSuccess(GROUP_ID, {
        balance: 1000,
        reserved: 0,
        available: 1000,
        recentLedger: [],
        leaderTermsAcceptedAt: "2026-05-01T10:00:00Z",
      }),
    );
    renderWithProviders(<GroupWalletPage publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("group-wallet-page")).toBeInTheDocument(),
    );
    expect(
      screen.queryByTestId("leader-terms-block-banner"),
    ).not.toBeInTheDocument();
  });
});
