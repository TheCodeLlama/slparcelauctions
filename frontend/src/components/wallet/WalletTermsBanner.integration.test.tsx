import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  waitFor,
  userEvent,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import type { AuthUser } from "@/lib/auth/session";
import type { WalletView } from "@/types/wallet";
import { WalletTermsBanner } from "./WalletTermsBanner";

const verifiedAuthUser: AuthUser = {
  publicId: "00000000-0000-0000-0000-00000000002a",
  username: "test-user",
  email: null,
  displayName: null,
  slAvatarUuid: "11111111-1111-1111-1111-111111111111",
  verified: true,
  role: "USER",
};

const staleUnverifiedAuthUser: AuthUser = {
  ...verifiedAuthUser,
  verified: false,
};

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

describe("WalletTermsBanner integration", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("appears when /me reports verified even though the bootstrap session is stale-unverified (regression)", async () => {
    // Seed the auth session as unverified (stale) but have /me return verified.
    // The banner must still appear because it should read from useCurrentUser,
    // not the stale auth-session bootstrap cache.
    server.use(
      userHandlers.meVerified(),
      http.get("*/api/v1/me/wallet", () =>
        HttpResponse.json(walletView({ termsAccepted: false })),
      ),
    );

    renderWithProviders(<WalletTermsBanner />, {
      auth: "authenticated",
      authUser: staleUnverifiedAuthUser,
    });

    // findByText waits for both /me and /wallet fetches to resolve.
    await screen.findByText(/Accept the SLParcels Wallet Terms of Use/i);
  });

  it("banner disappears after user accepts terms via the modal", async () => {
    // First GET returns termsAccepted:false; subsequent GETs (triggered by
    // invalidateQueries after the modal posts accept-terms) return
    // termsAccepted:true so the banner gates out and unmounts.
    let callCount = 0;
    server.use(
      userHandlers.meVerified(),
      http.get("*/api/v1/me/wallet", () => {
        callCount += 1;
        return HttpResponse.json(
          walletView({ termsAccepted: callCount === 1 ? false : true }),
        );
      }),
      http.post("*/api/v1/me/wallet/accept-terms", () => {
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderWithProviders(<WalletTermsBanner />, {
      auth: "authenticated",
      authUser: verifiedAuthUser,
    });

    // Banner must appear once the wallet query resolves with termsAccepted:false.
    await screen.findByText(/Accept the SLParcels Wallet Terms of Use/i);

    // Open the modal.
    await userEvent.click(
      screen.getByRole("button", { name: /Accept Wallet Terms/i }),
    );

    // Click the modal's accept button.
    await userEvent.click(
      screen.getByRole("button", { name: /^I Accept$/i }),
    );

    // After invalidateQueries refetches with termsAccepted:true the banner
    // should disappear.
    await waitFor(() =>
      expect(
        screen.queryByText(/Accept the SLParcels Wallet Terms of Use/i),
      ).not.toBeInTheDocument(),
    );
  });
});
