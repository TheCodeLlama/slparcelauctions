import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import type { AuthUser } from "@/lib/auth/session";
import { DisputeFormClient } from "./DisputeFormClient";
import { fakeEscrow } from "@/test/fixtures/escrow";

// -------------------------------------------------------------
// next/navigation is mocked globally in vitest.setup.ts, but each call
// to useRouter() there returns a fresh `push: vi.fn()` / `replace:
// vi.fn()` so assertions on those calls can't pin a single spy down.
// Override the mock locally with hoisted, stable spies so both the
// success / 409 routes and the unauthenticated redirect can be
// asserted. Mirror of EscrowPageClient.test.tsx.
// -------------------------------------------------------------
const { pushMock, replaceMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: pushMock,
    replace: replaceMock,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: vi.fn(() => "/auction/00000000-0000-0000-0000-000000000007/escrow/dispute"),
  useSearchParams: () => new URLSearchParams(),
}));

// Winner fixture — publicId does NOT match the sellerPublicId we pass into
// the client, so role resolves to `winner`. The winner is the natural
// dispute filer (seller-not-responsive, wrong-parcel, etc.), which matches
// the form's primary user flow.
const winnerUser: AuthUser = {
  publicId: "00000000-0000-0000-0000-0000000003e7",
  email: "winner@example.com",
  displayName: "Winner",
  slAvatarUuid: "99999999-9999-9999-9999-999999999999",
  verified: true,
  role: "USER",
};

describe("DisputeFormClient", () => {
  beforeEach(() => {
    pushMock.mockReset();
    replaceMock.mockReset();
  });

  it("renders the dispute form for an ESCROW_PENDING escrow", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/escrow", () =>
        HttpResponse.json(fakeEscrow({ auctionPublicId: "00000000-0000-0000-0000-000000000007", state: "ESCROW_PENDING" })),
      ),
    );

    renderWithProviders(<DisputeFormClient auctionPublicId="00000000-0000-0000-0000-000000000007" sellerPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "authenticated",
      authUser: winnerUser,
    });

    // Form controls + submit button are visible.
    expect(
      await screen.findByLabelText(/reason/i),
    ).toBeInTheDocument();
    expect(screen.getByLabelText(/description/i)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /file dispute/i }),
    ).toBeInTheDocument();
  });

  it.each(["COMPLETED", "DISPUTED", "EXPIRED", "FROZEN"] as const)(
    "shows a non-disputable panel for %s state (no form)",
    async (state) => {
      server.use(
        http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/escrow", () =>
          HttpResponse.json(
            fakeEscrow({
              auctionPublicId: "00000000-0000-0000-0000-000000000007",
              state,
              // Stamp the terminal timestamp that matches the state so the
              // panel's copy has a non-dash date where applicable.
              completedAt:
                state === "COMPLETED" ? new Date().toISOString() : null,
              disputedAt:
                state === "DISPUTED" ? new Date().toISOString() : null,
              expiredAt:
                state === "EXPIRED" ? new Date().toISOString() : null,
              frozenAt:
                state === "FROZEN" ? new Date().toISOString() : null,
            }),
          ),
        ),
      );

      renderWithProviders(<DisputeFormClient auctionPublicId="00000000-0000-0000-0000-000000000007" sellerPublicId="00000000-0000-0000-0000-00000000002a" />, {
        auth: "authenticated",
        authUser: winnerUser,
      });

      expect(
        await screen.findByText(/can no longer be disputed/i),
      ).toBeInTheDocument();
      // No form is rendered.
      expect(screen.queryByLabelText(/reason/i)).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: /file dispute/i }),
      ).not.toBeInTheDocument();
    },
  );

  it("surfaces a Zod error when description is shorter than 10 characters", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/escrow", () =>
        HttpResponse.json(fakeEscrow({ auctionPublicId: "00000000-0000-0000-0000-000000000007", state: "ESCROW_PENDING" })),
      ),
      http.post("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/escrow/dispute", () => {
        // Should never be hit — Zod should block the submit.
        throw new Error("Submit should be blocked by client-side validation");
      }),
    );

    renderWithProviders(<DisputeFormClient auctionPublicId="00000000-0000-0000-0000-000000000007" sellerPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "authenticated",
      authUser: winnerUser,
    });

    const textarea = await screen.findByLabelText(/description/i);
    await user.type(textarea, "too short");
    await user.click(screen.getByRole("button", { name: /file dispute/i }));

    expect(
      await screen.findByText(/at least 10 characters/i),
    ).toBeInTheDocument();
  });

  it("routes back to the escrow page and shows a success toast on 2xx", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/escrow", () =>
        HttpResponse.json(fakeEscrow({ auctionPublicId: "00000000-0000-0000-0000-000000000007", state: "ESCROW_PENDING" })),
      ),
      http.post("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/escrow/dispute", () =>
        HttpResponse.json(
          fakeEscrow({
            auctionPublicId: "00000000-0000-0000-0000-000000000007",
            state: "DISPUTED",
            disputedAt: new Date().toISOString(),
            disputeReasonCategory: "SELLER_NOT_RESPONSIVE",
            disputeDescription: "Seller went dark after I paid in-world.",
          }),
        ),
      ),
    );

    renderWithProviders(<DisputeFormClient auctionPublicId="00000000-0000-0000-0000-000000000007" sellerPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "authenticated",
      authUser: winnerUser,
    });

    const reason = await screen.findByLabelText(/reason/i);
    await user.selectOptions(reason, "SELLER_NOT_RESPONSIVE");
    await user.type(
      screen.getByLabelText(/description/i),
      "Seller went dark after I paid in-world.",
    );
    await user.click(screen.getByRole("button", { name: /file dispute/i }));

    await waitFor(() => {
      expect(pushMock).toHaveBeenCalledWith("/auction/00000000-0000-0000-0000-000000000007/escrow");
    });
    expect(
      await screen.findByText(/dispute filed/i),
    ).toBeInTheDocument();
  });

  it("routes back to the escrow page on 409 ESCROW_INVALID_TRANSITION", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/escrow", () =>
        HttpResponse.json(fakeEscrow({ auctionPublicId: "00000000-0000-0000-0000-000000000007", state: "ESCROW_PENDING" })),
      ),
      http.post("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/escrow/dispute", () =>
        HttpResponse.json(
          {
            status: 409,
            code: "ESCROW_INVALID_TRANSITION",
            title: "Conflict",
            detail: "escrow can no longer be disputed",
          },
          { status: 409 },
        ),
      ),
    );

    renderWithProviders(<DisputeFormClient auctionPublicId="00000000-0000-0000-0000-000000000007" sellerPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "authenticated",
      authUser: winnerUser,
    });

    await user.type(
      await screen.findByLabelText(/description/i),
      "State changed under me; filing anyway.",
    );
    await user.click(screen.getByRole("button", { name: /file dispute/i }));

    await waitFor(() => {
      expect(pushMock).toHaveBeenCalledWith("/auction/00000000-0000-0000-0000-000000000007/escrow");
    });
    // Error toast surfaces alongside the redirect so the user understands
    // why they were bounced back.
    expect(
      await screen.findByText(/escrow's state changed/i),
    ).toBeInTheDocument();
  });

  it("redirects anonymous users to /login with the dispute path as the returnTo", async () => {
    // The escrow GET should never fire for an anonymous caller — the
    // useQuery is gated on `isAuthenticated` — but stub it anyway so a
    // regression (e.g. the gate being removed) surfaces as an unhandled-
    // MSW error rather than a silent fetch.
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/escrow", () =>
        HttpResponse.json(fakeEscrow({ auctionPublicId: "00000000-0000-0000-0000-000000000007" })),
      ),
    );

    renderWithProviders(<DisputeFormClient auctionPublicId="00000000-0000-0000-0000-000000000007" sellerPublicId="00000000-0000-0000-0000-00000000002a" />, {
      auth: "anonymous",
    });

    await waitFor(() => {
      expect(replaceMock).toHaveBeenCalledWith(
        `/login?next=${encodeURIComponent("/auction/00000000-0000-0000-0000-000000000007/escrow/dispute")}`,
      );
    });
  });
});
