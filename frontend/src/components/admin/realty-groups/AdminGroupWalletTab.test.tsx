import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { AdminGroupWalletTab } from "./AdminGroupWalletTab";

const GROUP_ID = "00000000-0000-4000-8000-0000000000aa";

describe("AdminGroupWalletTab", () => {
  it("renders the adjust card and shows the latest balance after a successful adjustment", async () => {
    server.use(
      http.post(
        `*/api/v1/admin/realty-groups/${GROUP_ID}/wallet/adjust`,
        async ({ request }) => {
          const body = (await request.json()) as {
            amount: number;
            reason: string;
          };
          return HttpResponse.json({
            balance: body.amount,
            reserved: 0,
            available: body.amount,
            leaderTermsAcceptedAt: null,
            recentLedger: [],
          });
        },
      ),
    );

    renderWithProviders(<AdminGroupWalletTab groupPublicId={GROUP_ID} />);

    // Balance card is hidden until the first adjustment lands.
    expect(
      screen.queryByTestId("admin-group-wallet-tab-balance"),
    ).not.toBeInTheDocument();

    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-amount"),
      "1500",
    );
    await userEvent.type(
      screen.getByTestId("admin-wallet-adjust-reason"),
      "Reimburse fee",
    );
    await userEvent.click(screen.getByTestId("admin-wallet-adjust-submit"));

    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-wallet-tab-balance"),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByTestId("admin-group-wallet-tab-available").textContent,
    ).toMatch(/L\$1,500/);
    expect(
      screen.getByTestId("admin-group-wallet-tab-balance-value").textContent,
    ).toMatch(/L\$1,500/);
  });

  it("renders the adjust card on first mount with no prior balance", () => {
    renderWithProviders(<AdminGroupWalletTab groupPublicId={GROUP_ID} />);
    expect(
      screen.getByTestId("admin-wallet-adjust-card"),
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId("admin-group-wallet-tab-balance"),
    ).not.toBeInTheDocument();
  });

  it("renders the current balance from the admin GET wallet endpoint on mount", async () => {
    server.use(
      http.get(
        `*/api/v1/admin/realty-groups/${GROUP_ID}/wallet`,
        () =>
          HttpResponse.json({
            balance: 4200,
            reserved: 200,
            available: 4000,
            leaderTermsAcceptedAt: null,
            recentLedger: [],
          }),
      ),
    );

    renderWithProviders(<AdminGroupWalletTab groupPublicId={GROUP_ID} />);

    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-wallet-tab-balance"),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByTestId("admin-group-wallet-tab-available").textContent,
    ).toMatch(/L\$4,000/);
    expect(
      screen.getByTestId("admin-group-wallet-tab-balance-value").textContent,
    ).toMatch(/L\$4,200/);
    expect(
      screen.getByTestId("admin-group-wallet-tab-reserved").textContent,
    ).toMatch(/L\$200/);
  });
});
