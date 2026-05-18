import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { fakeSellerAuction } from "@/test/fixtures/auction";
import type { SellerAuctionResponse } from "@/types/auction";
import { ActivateClient } from "./ActivateClient";

const push = vi.fn();
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push,
    replace,
    back: vi.fn(),
    refresh: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/listings/42/activate",
  useSearchParams: () => new URLSearchParams(),
}));

// Seller-facing auction fixture. Delegates to the shared
// `fakeSellerAuction` builder so the enriched `seller` block (Epic 07
// sub-spec 1 Task 2) is present for every consumer in this file.
function auctionBase(
  overrides: Partial<SellerAuctionResponse> = {},
): SellerAuctionResponse {
  return fakeSellerAuction(overrides);
}

describe("ActivateClient", () => {
  beforeEach(() => {
    push.mockReset();
    replace.mockReset();
    server.use(
      http.get("*/api/v1/config/listing-fee", () =>
        HttpResponse.json({ amountLindens: 100 }),
      ),
    );
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("DRAFT renders the rich draft editor with sample-data banner + List Parcel action", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a", () =>
        HttpResponse.json(auctionBase({ status: "DRAFT" })),
      ),
      http.get("*/api/v1/me/wallet", () =>
        HttpResponse.json({
          balance: 500,
          reserved: 0,
          available: 500,
          penaltyOwed: 0,
          queuedForWithdrawal: 0,
          termsAccepted: true,
          termsVersion: "v1.0",
          termsAcceptedAt: "2026-04-17T00:00:00Z",
          recentLedger: [],
        }),
      ),
    );
    renderWithProviders(
      <ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />,
      { auth: "authenticated" },
    );
    expect(
      await screen.findByTestId("draft-action-bar-list"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("draft-action-bar-delete")).toBeInTheDocument();
  });

  it("DRAFT preview renders the real seller card stats, not the You placeholder", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a", () =>
        HttpResponse.json(auctionBase({ status: "DRAFT" })),
      ),
      http.get("*/api/v1/me/wallet", () =>
        HttpResponse.json({
          balance: 500,
          reserved: 0,
          available: 500,
          penaltyOwed: 0,
          queuedForWithdrawal: 0,
          termsAccepted: true,
          termsVersion: "v1.0",
          termsAcceptedAt: "2026-04-17T00:00:00Z",
          recentLedger: [],
        }),
      ),
    );
    renderWithProviders(
      <ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />,
      { auth: "authenticated" },
    );

    const card = await screen.findByTestId("seller-profile-card");
    expect(card).toHaveTextContent("Test Seller");
    expect(card).toHaveTextContent("7 completed sale");
    expect(card).toHaveTextContent("Completion rate: 92%");
    // The defensive "You" placeholder branch must not be the one rendered
    // once the backend supplies the enriched seller block.
    expect(card).not.toHaveTextContent(/\bYou\b/);
  });

  it("DRAFT_PAID renders the Verify-ownership button and a click flips the status to ACTIVE", async () => {
    let calls = 0;
    let verifyHits = 0;
    server.use(
      http.get(
        "*/api/v1/auctions/00000000-0000-0000-0000-00000000002a",
        () => {
          calls += 1;
          // Only the initial fetch is served; on success the mutation seeds
          // the cache with the ACTIVE auction so subsequent polls never need
          // a 2nd response.
          return HttpResponse.json(auctionBase({ status: "DRAFT_PAID" }));
        },
      ),
      http.put(
        "*/api/v1/auctions/00000000-0000-0000-0000-00000000002a/verify",
        () => {
          verifyHits += 1;
          return HttpResponse.json(
            auctionBase({ status: "ACTIVE", verificationTier: "SCRIPT" }),
          );
        },
      ),
    );
    renderWithProviders(
      <ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />,
      { auth: "authenticated" },
    );

    const button = await screen.findByTestId("verify-ownership-button");
    expect(button).toHaveTextContent(/Verify ownership/i);
    await userEvent.click(button);

    await waitFor(() => expect(verifyHits).toBe(1));
    await waitFor(() =>
      expect(screen.getByText(/Your listing is live/i)).toBeInTheDocument(),
    );
    expect(calls).toBeGreaterThan(0);
  });

  it("VERIFICATION_FAILED renders the failure notes plus a Retry button", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a", () =>
        HttpResponse.json(
          auctionBase({
            status: "VERIFICATION_FAILED",
            verificationNotes:
              "Ownership check failed: SL API returned a different owner.",
          }),
        ),
      ),
    );
    renderWithProviders(
      <ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />,
      { auth: "authenticated" },
    );

    expect(
      await screen.findByText(/Ownership check failed/i),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/no additional fee is required/i),
    ).toBeInTheDocument();
    const retry = screen.getByTestId("verify-ownership-button");
    expect(retry).toHaveTextContent(/Retry verify/i);
  });

  it("VERIFICATION_FAILED Retry button hits the same PUT /verify endpoint with no body", async () => {
    let observedBody: unknown = "not-called";
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a", () =>
        HttpResponse.json(
          auctionBase({
            status: "VERIFICATION_FAILED",
            verificationNotes:
              "Ownership check failed: SL API returned a different owner.",
          }),
        ),
      ),
      http.put(
        "*/api/v1/auctions/00000000-0000-0000-0000-00000000002a/verify",
        async ({ request }) => {
          observedBody = await request.json().catch(() => null);
          return HttpResponse.json(
            auctionBase({ status: "ACTIVE", verificationTier: "SCRIPT" }),
          );
        },
      ),
    );
    renderWithProviders(
      <ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />,
      { auth: "authenticated" },
    );
    const retry = await screen.findByTestId("verify-ownership-button");
    await userEvent.click(retry);
    await waitFor(() =>
      expect(screen.getByText(/Your listing is live/i)).toBeInTheDocument(),
    );
    // The body is an empty object — the backend ignores the payload entirely.
    expect(observedBody).toEqual({});
  });

  it("ACTIVE renders the success screen with both actions", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a", () =>
        HttpResponse.json(auctionBase({ status: "ACTIVE" })),
      ),
    );
    renderWithProviders(
      <ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />,
      { auth: "authenticated" },
    );
    expect(
      await screen.findByText(/Your listing is live/i),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Back to My Listings/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /View public listing/i }),
    ).toBeInTheDocument();
  });

  it("CANCELLED redirects to the dashboard listings tab", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000002a", () =>
        HttpResponse.json(auctionBase({ status: "CANCELLED" })),
      ),
    );
    renderWithProviders(
      <ActivateClient auctionPublicId="00000000-0000-0000-0000-00000000002a" />,
      { auth: "authenticated" },
    );
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith("/dashboard/listings"),
    );
  });
});
