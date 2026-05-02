import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";
import type { CurrentUser } from "@/lib/user/api";
import { SuspensionBanner } from "./SuspensionBanner";

function makeMe(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    ...mockVerifiedCurrentUser,
    ...overrides,
  };
}

function farFutureIso(): string {
  return new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
}

describe("SuspensionBanner", () => {
  it("returns null while the /me query is still loading", () => {
    server.use(
      http.get("*/api/v1/users/me", async () => {
        // Delay so the query never resolves before the assertion runs.
        await new Promise((r) => setTimeout(r, 200));
        return HttpResponse.json(makeMe());
      }),
    );
    renderWithProviders(<SuspensionBanner />, { auth: "authenticated" });
    expect(screen.queryByTestId("suspension-banner")).not.toBeInTheDocument();
  });

  it("returns null when the user has a clean account", async () => {
    server.use(
      http.get("*/api/v1/users/me", () => HttpResponse.json(makeMe())),
    );
    renderWithProviders(<SuspensionBanner />, { auth: "authenticated" });
    // Wait for a turn so the query has a chance to resolve, then assert null.
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByTestId("suspension-banner")).not.toBeInTheDocument();
  });

  it("renders the permanent-ban variant when bannedFromListing is true", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({
            bannedFromListing: true,
            // Banned takes precedence even when other suspensions are set.
            penaltyBalanceOwed: 1000,
            listingSuspensionUntil: farFutureIso(),
          }),
        ),
      ),
    );
    renderWithProviders(<SuspensionBanner />, { auth: "authenticated" });
    const banner = await screen.findByTestId("suspension-banner");
    expect(banner.dataset.variant).toBe("banned");
    expect(banner.className).toMatch(/danger-bg/);
    expect(banner).toHaveTextContent(
      /listing privileges have been permanently suspended/i,
    );
    expect(banner).toHaveTextContent(/contact support/i);
  });

  it("renders the timed-and-debt variant when both suspensions are active", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({
            bannedFromListing: false,
            penaltyBalanceOwed: 2500,
            listingSuspensionUntil: farFutureIso(),
          }),
        ),
      ),
    );
    renderWithProviders(<SuspensionBanner />, { auth: "authenticated" });
    const banner = await screen.findByTestId("suspension-banner");
    expect(banner.dataset.variant).toBe("timed-and-debt");
    expect(banner.className).toMatch(/info-bg/);
    expect(banner).toHaveTextContent(/listing suspended until/i);
    expect(banner).toHaveTextContent(/L\$2,500/);
    expect(banner).toHaveTextContent(/visit any slpa terminal/i);
  });

  it("renders the timed-only variant when only the suspension is active", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({
            bannedFromListing: false,
            penaltyBalanceOwed: 0,
            listingSuspensionUntil: farFutureIso(),
          }),
        ),
      ),
    );
    renderWithProviders(<SuspensionBanner />, { auth: "authenticated" });
    const banner = await screen.findByTestId("suspension-banner");
    expect(banner.dataset.variant).toBe("timed-only");
    expect(banner).toHaveTextContent(/listing suspended until/i);
    expect(banner).not.toHaveTextContent(/L\$/);
  });

  it("renders the debt-only variant when only the penalty is outstanding", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({
            bannedFromListing: false,
            penaltyBalanceOwed: 1000,
            listingSuspensionUntil: null,
          }),
        ),
      ),
    );
    renderWithProviders(<SuspensionBanner />, { auth: "authenticated" });
    const banner = await screen.findByTestId("suspension-banner");
    expect(banner.dataset.variant).toBe("debt-only");
    expect(banner).toHaveTextContent(/L\$1,000 in cancellation penalties/i);
    expect(banner).toHaveTextContent(/visit any slpa terminal/i);
  });

  it("treats an expired listingSuspensionUntil as no timed suspension", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json(
          makeMe({
            bannedFromListing: false,
            penaltyBalanceOwed: 0,
            listingSuspensionUntil: "2020-01-01T00:00:00Z",
          }),
        ),
      ),
    );
    renderWithProviders(<SuspensionBanner />, { auth: "authenticated" });
    await waitFor(() => {
      expect(screen.queryByTestId("suspension-banner")).not.toBeInTheDocument();
    });
  });
});
