import { describe, it, expect, vi, beforeEach } from "vitest";
import { act } from "react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type { AuthUser } from "@/lib/auth/session";
import { EscrowPageClient } from "./EscrowPageClient";
import { fakeEscrow, fakeEscrowEnvelope } from "@/test/fixtures/escrow";

// -------------------------------------------------------------
// WebSocket module mock. Captures the last envelope callback so
// individual tests can drive ESCROW_* envelopes without running
// real STOMP / SockJS plumbing. Same pattern as the auction-detail
// integration test.
// -------------------------------------------------------------
const { subscribeMock, subscribeToConnectionStateMock, getConnectionStateMock } =
  vi.hoisted(() => {
    type WsStatus =
      | "disconnected"
      | "connecting"
      | "connected"
      | "reconnecting"
      | "error";
    return {
      subscribeMock: vi.fn(),
      subscribeToConnectionStateMock: vi.fn(),
      getConnectionStateMock: vi.fn(() => ({
        status: "connected" as WsStatus,
      })),
    };
  });

vi.mock("@/lib/ws/client", () => ({
  subscribe: (...args: unknown[]) => subscribeMock(...args),
  subscribeToConnectionState: (
    listener: (state: { status: string }) => void,
  ) => subscribeToConnectionStateMock(listener),
  getConnectionState: getConnectionStateMock,
}));

// next/navigation is mocked globally in vitest.setup.ts, but each call to
// useRouter() there returns a fresh `replace: vi.fn()` so assertions on
// redirect calls can't pin it down. Override the mock locally with a
// hoisted, stable replaceMock so the unauthenticated-redirect test can
// assert on the exact invocation. Other navigation helpers the module
// exports are included for parity with the setup mock.
const { replaceMock } = vi.hoisted(() => ({ replaceMock: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: vi.fn(),
    replace: replaceMock,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: vi.fn(() => "/auction/7/escrow"),
  useSearchParams: () => new URLSearchParams(),
}));

// Winner fixture — id does NOT match sellerId so the client resolves
// the viewer role to `winner`. renderWithProviders seeds the session
// cache directly, so the session.user.id drives role derivation.
const winnerUser: AuthUser = {
  id: 999,
  email: "winner@example.com",
  displayName: "Winner",
  slAvatarUuid: "99999999-9999-9999-9999-999999999999",
  verified: true,
};

// Seller fixture with an id that matches the fixture sellerId (42).
const sellerUser: AuthUser = {
  id: 42,
  email: "seller@example.com",
  displayName: "Seller",
  slAvatarUuid: "42424242-4242-4242-4242-424242424242",
  verified: true,
};

describe("EscrowPageClient", () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    subscribeMock.mockImplementation(() => () => {});
    subscribeToConnectionStateMock.mockReset();
    subscribeToConnectionStateMock.mockImplementation((listener) => {
      listener({ status: "connected" });
      return () => {};
    });
    getConnectionStateMock.mockReset();
    getConnectionStateMock.mockReturnValue({ status: "connected" });
    replaceMock.mockReset();
  });

  it("renders pending state for winner", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(fakeEscrow({ auctionId: 7, state: "ESCROW_PENDING" })),
      ),
    );
    renderWithProviders(
      <EscrowPageClient auctionId={7} sellerId={42} />,
      { auth: "authenticated", authUser: winnerUser },
    );
    // PendingStateCard winner headline is "Pay L$ 5,000" — match the
    // "Pay L$" prefix case-insensitively so the locale-formatted amount
    // doesn't break the assertion.
    expect(await screen.findByText(/pay l\$/i)).toBeInTheDocument();
    // Header renders the role label — confirms sellerId-vs-user.id
    // resolution picked `winner`.
    expect(screen.getByText(/escrow · winner/i)).toBeInTheDocument();
  });

  it("invalidates the reviews query on REVIEW_REVEALED envelope", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(
          fakeEscrow({
            auctionId: 7,
            state: "COMPLETED",
            completedAt: "2026-04-18T12:00:00Z",
          }),
        ),
      ),
    );
    let reviewsFetchCount = 0;
    server.use(
      http.get("*/api/v1/auctions/7/reviews", () => {
        reviewsFetchCount += 1;
        return HttpResponse.json({
          reviews: [],
          myPendingReview: null,
          canReview: true,
          windowClosesAt: "2026-05-10T00:00:00Z",
        });
      }),
    );

    renderWithProviders(
      <EscrowPageClient auctionId={7} sellerId={42} />,
      { auth: "authenticated", authUser: winnerUser },
    );

    // ReviewPanel renders once escrow lands in COMPLETED; the panel's
    // useAuctionReviews hook fires the first GET.
    await waitFor(() => expect(reviewsFetchCount).toBeGreaterThan(0));
    const initial = reviewsFetchCount;

    const handler = subscribeMock.mock.calls[0]?.[1] as (
      env: unknown,
    ) => void;
    expect(handler).toBeDefined();
    act(() => {
      handler({
        type: "REVIEW_REVEALED",
        auctionId: 7,
        reviewId: 1,
        reviewerId: 999,
        revieweeId: 42,
        reviewedRole: "SELLER",
        revealedAt: "2026-04-20T10:00:00Z",
      });
    });

    // The handler branch invalidates ["reviews","auction","7"], which
    // refetches the panel's envelope — assert on the second GET.
    await waitFor(() => expect(reviewsFetchCount).toBeGreaterThan(initial));
  });

  it("invalidates cache on ESCROW_FUNDED envelope and refetches", async () => {
    let callCount = 0;
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () => {
        callCount += 1;
        if (callCount === 1) {
          return HttpResponse.json(
            fakeEscrow({ auctionId: 7, state: "ESCROW_PENDING" }),
          );
        }
        return HttpResponse.json(
          fakeEscrow({
            auctionId: 7,
            state: "TRANSFER_PENDING",
            fundedAt: new Date().toISOString(),
            transferDeadline: new Date(
              Date.now() + 72 * 3_600_000,
            ).toISOString(),
          }),
        );
      }),
    );

    renderWithProviders(
      <EscrowPageClient auctionId={7} sellerId={42} />,
      { auth: "authenticated", authUser: winnerUser },
    );

    // First render lands on the pending-winner card.
    await screen.findByText(/pay l\$/i);

    // Drive the captured envelope handler. subscribe was called with
    // (destination, onMessage) — pull the second arg off call 0.
    const firstCall = subscribeMock.mock.calls[0];
    expect(firstCall).toBeDefined();
    const handler = firstCall[1] as (env: unknown) => void;
    act(() => {
      handler(fakeEscrowEnvelope("ESCROW_FUNDED", { auctionId: 7 }));
    });

    // The invalidation triggers the second MSW response (TRANSFER_PENDING
    // pre-confirmation, winner variant) — headline is "Waiting for
    // seller to transfer the parcel".
    await waitFor(() => {
      expect(
        screen.getByText(/waiting for seller to transfer the parcel/i),
      ).toBeInTheDocument();
    });
  });

  it("shows empty state on 404", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(
          { status: 404, code: "ESCROW_NOT_FOUND", title: "Not Found" },
          { status: 404 },
        ),
      ),
    );
    renderWithProviders(
      <EscrowPageClient auctionId={7} sellerId={42} />,
      { auth: "authenticated", authUser: winnerUser },
    );
    expect(
      await screen.findByText(/no escrow for this auction/i),
    ).toBeInTheDocument();
  });

  it("shows error state on 403", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(
          {
            status: 403,
            code: "ESCROW_FORBIDDEN",
            title: "Forbidden",
            detail: "Not a party to this escrow",
          },
          { status: 403 },
        ),
      ),
    );
    renderWithProviders(
      <EscrowPageClient auctionId={7} sellerId={42} />,
      { auth: "authenticated", authUser: winnerUser },
    );
    expect(
      await screen.findByText(/could not load escrow status/i),
    ).toBeInTheDocument();
  });

  it("resolves role=seller when user.id matches sellerId", async () => {
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(fakeEscrow({ auctionId: 7, state: "ESCROW_PENDING" })),
      ),
    );
    renderWithProviders(
      <EscrowPageClient auctionId={7} sellerId={sellerUser.id} />,
      { auth: "authenticated", authUser: sellerUser },
    );
    // Seller variant of the PendingStateCard headline.
    expect(
      await screen.findByText(/awaiting payment from/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/escrow · seller/i)).toBeInTheDocument();
  });

  it("redirects anonymous users to login with return path", async () => {
    // The escrow GET should never fire for an anonymous caller — the
    // useQuery is gated on `isAuthenticated` — but stub it anyway so a
    // regression (e.g. the gate being removed) surfaces as a handler-not-
    // found MSW error rather than a silent fetch.
    server.use(
      http.get("*/api/v1/auctions/7/escrow", () =>
        HttpResponse.json(fakeEscrow({ auctionId: 7 })),
      ),
    );

    renderWithProviders(<EscrowPageClient auctionId={7} sellerId={42} />, {
      auth: "anonymous",
    });

    await waitFor(() => {
      expect(replaceMock).toHaveBeenCalledWith(
        expect.stringMatching(/^\/login\?next=/),
      );
    });
    // The returnTo is the URL-encoded path back to this page so the
    // post-login hop lands the viewer where they started.
    expect(replaceMock).toHaveBeenCalledWith(
      `/login?next=${encodeURIComponent("/auction/7/escrow")}`,
    );
  });
});
