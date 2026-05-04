import { describe, expect, it, vi } from "vitest";
import { act, type ReactElement, type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { ToastProvider } from "@/components/ui/Toast";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type { AuthUser } from "@/lib/auth/session";
import type { AuctionReviewsResponse, ReviewDto } from "@/types/review";
import {
  deriveReviewPanelState,
  ReviewPanel,
} from "./ReviewPanel";

// --------------------------------------------------------------------------
// Fixtures
// --------------------------------------------------------------------------

const partyUser: AuthUser = {
  publicId: "00000000-0000-0000-0000-00000000002a",
  email: "party@example.com",
  displayName: "Party",
  slAvatarUuid: null,
  verified: true,
  role: "USER",
};

function makeReview(overrides: Partial<ReviewDto> = {}): ReviewDto {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    auctionPublicId: "00000000-0000-0000-0000-000000000007",
    auctionTitle: "Aurora Parcel",
    auctionPrimaryPhotoUrl: null,
    reviewerPublicId: "00000000-0000-0000-0000-00000000002a",
    reviewerDisplayName: "Party",
    reviewerAvatarUrl: null,
    revieweePublicId: "00000000-0000-0000-0000-00000000004d",
    reviewedRole: "SELLER",
    rating: 5,
    text: "Smooth transaction.",
    visible: true,
    pending: false,
    submittedAt: "2026-04-18T12:00:00Z",
    revealedAt: "2026-04-19T12:00:00Z",
    response: null,
    ...overrides,
  };
}

function stubEnvelope(
  envelope: AuctionReviewsResponse,
  { auctionPublicId = "00000000-0000-0000-0000-000000000007" }: { auctionPublicId?: string } = {},
) {
  server.use(
    http.get(`*/api/v1/auctions/${auctionPublicId}/reviews`, () =>
      HttpResponse.json(envelope),
    ),
  );
}

// --------------------------------------------------------------------------
// Pure helper tests
// --------------------------------------------------------------------------

describe("deriveReviewPanelState", () => {
  const NOW = new Date("2026-05-01T12:00:00Z");
  const future = "2026-05-10T00:00:00Z";
  const past = "2026-04-20T00:00:00Z";

  it("returns loading when envelope is undefined", () => {
    expect(deriveReviewPanelState(undefined, true, NOW)).toBe("loading");
  });

  it("returns submit when canReview is true", () => {
    expect(
      deriveReviewPanelState(
        {
          reviews: [],
          myPendingReview: null,
          canReview: true,
          windowClosesAt: future,
        },
        true,
        NOW,
      ),
    ).toBe("submit");
  });

  it("returns pending when myPendingReview is set", () => {
    expect(
      deriveReviewPanelState(
        {
          reviews: [],
          myPendingReview: makeReview({ visible: false, pending: true }),
          canReview: false,
          windowClosesAt: future,
        },
        true,
        NOW,
      ),
    ).toBe("pending");
  });

  it("returns revealed-both when reviews.length >= 2", () => {
    expect(
      deriveReviewPanelState(
        {
          reviews: [makeReview({ publicId: "00000000-0000-0000-0000-000000000001" }), makeReview({ publicId: "00000000-0000-0000-0000-000000000002" })],
          myPendingReview: null,
          canReview: false,
          windowClosesAt: past,
        },
        true,
        NOW,
      ),
    ).toBe("revealed-both");
  });

  it("returns revealed-one when reviews.length === 1", () => {
    expect(
      deriveReviewPanelState(
        {
          reviews: [makeReview()],
          myPendingReview: null,
          canReview: false,
          windowClosesAt: past,
        },
        true,
        NOW,
      ),
    ).toBe("revealed-one");
  });

  it("returns window-closed-none for parties with elapsed windowClosesAt and no reviews", () => {
    expect(
      deriveReviewPanelState(
        {
          reviews: [],
          myPendingReview: null,
          canReview: false,
          windowClosesAt: past,
        },
        true,
        NOW,
      ),
    ).toBe("window-closed-none");
  });

  it("returns read-only for non-parties even when the window is closed", () => {
    expect(
      deriveReviewPanelState(
        {
          reviews: [],
          myPendingReview: null,
          canReview: false,
          windowClosesAt: past,
        },
        false,
        NOW,
      ),
    ).toBe("read-only");
  });
});

// --------------------------------------------------------------------------
// Rendering tests — one per derived state
// --------------------------------------------------------------------------

describe("ReviewPanel", () => {
  it("renders the hash-scroll id on the panel root", async () => {
    stubEnvelope({
      reviews: [],
      myPendingReview: null,
      canReview: true,
      windowClosesAt: "2026-05-10T00:00:00Z",
    });
    renderWithProviders(<ReviewPanel auctionPublicId="00000000-0000-0000-0000-000000000007" isParty />, {
      auth: "authenticated",
      authUser: partyUser,
    });
    const panel = await screen.findByTestId("review-panel");
    expect(panel).toHaveAttribute("id", "review-panel");
  });

  it("renders the submit state when canReview=true and no pending review", async () => {
    stubEnvelope({
      reviews: [],
      myPendingReview: null,
      canReview: true,
      windowClosesAt: "2026-05-10T00:00:00Z",
    });
    renderWithProviders(<ReviewPanel auctionPublicId="00000000-0000-0000-0000-000000000007" isParty />, {
      auth: "authenticated",
      authUser: partyUser,
    });
    expect(
      await screen.findByTestId("review-panel-submit"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("review-panel-submit-button"),
    ).toBeDisabled();
  });

  it("enables submit once a rating is selected", async () => {
    stubEnvelope({
      reviews: [],
      myPendingReview: null,
      canReview: true,
      windowClosesAt: "2026-05-10T00:00:00Z",
    });
    const user = userEvent.setup();
    renderWithProviders(<ReviewPanel auctionPublicId="00000000-0000-0000-0000-000000000007" isParty />, {
      auth: "authenticated",
      authUser: partyUser,
    });
    await screen.findByTestId("review-panel-submit");
    await user.click(screen.getByTestId("star-selector-4"));
    expect(
      screen.getByTestId("review-panel-submit-button"),
    ).not.toBeDisabled();
  });

  it("POSTs the review on submit click", async () => {
    stubEnvelope({
      reviews: [],
      myPendingReview: null,
      canReview: true,
      windowClosesAt: "2026-05-10T00:00:00Z",
    });
    const submitSpy = vi.fn();
    server.use(
      http.post("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/reviews", async ({ request }) => {
        submitSpy(await request.json());
        return HttpResponse.json(
          makeReview({ visible: false, pending: true, rating: 4 }),
        );
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ReviewPanel auctionPublicId="00000000-0000-0000-0000-000000000007" isParty />, {
      auth: "authenticated",
      authUser: partyUser,
    });
    await screen.findByTestId("review-panel-submit");
    await user.click(screen.getByTestId("star-selector-4"));
    await user.type(
      screen.getByTestId("review-panel-submit-text"),
      "Great seller",
    );
    await user.click(screen.getByTestId("review-panel-submit-button"));
    await waitFor(() => expect(submitSpy).toHaveBeenCalledOnce());
    expect(submitSpy).toHaveBeenCalledWith({
      rating: 4,
      text: "Great seller",
    });
  });

  it("renders the pending state with the viewer's own rating + text", async () => {
    const pending = makeReview({
      publicId: "00000000-0000-0000-0000-000000000063",
      rating: 4,
      text: "Line one.\nLine two.",
      visible: false,
      pending: true,
    });
    stubEnvelope({
      reviews: [],
      myPendingReview: pending,
      canReview: false,
      windowClosesAt: "2026-05-10T00:00:00Z",
    });
    renderWithProviders(<ReviewPanel auctionPublicId="00000000-0000-0000-0000-000000000007" isParty />, {
      auth: "authenticated",
      authUser: partyUser,
    });
    expect(
      await screen.findByTestId("review-panel-pending"),
    ).toBeInTheDocument();
    const text = screen.getByTestId("review-panel-pending-text");
    expect(text.textContent).toContain("Line one.");
    expect(text.className).toMatch(/whitespace-pre-wrap/);
  });

  it("renders revealed-both when there are two visible reviews", async () => {
    stubEnvelope({
      reviews: [
        makeReview({ publicId: "00000000-0000-0000-0000-000000000001", reviewerPublicId: "00000000-0000-0000-0000-00000000002a", reviewerDisplayName: "Party" }),
        makeReview({
          publicId: "00000000-0000-0000-0000-000000000002",
          reviewerPublicId: "00000000-0000-0000-0000-00000000004d",
          reviewerDisplayName: "Other",
          reviewedRole: "BUYER",
        }),
      ],
      myPendingReview: null,
      canReview: false,
      windowClosesAt: "2026-04-15T00:00:00Z",
    });
    renderWithProviders(<ReviewPanel auctionPublicId="00000000-0000-0000-0000-000000000007" isParty />, {
      auth: "authenticated",
      authUser: partyUser,
    });
    expect(
      await screen.findByTestId("review-panel-revealed"),
    ).toBeInTheDocument();
    expect(screen.getAllByTestId("review-card")).toHaveLength(2);
  });

  it("renders revealed-one with the window-closed note when the viewer never submitted", async () => {
    stubEnvelope({
      reviews: [makeReview({ publicId: "00000000-0000-0000-0000-000000000001", reviewerPublicId: "00000000-0000-0000-0000-00000000004d" })],
      myPendingReview: null,
      canReview: false,
      windowClosesAt: "2026-04-15T00:00:00Z",
    });
    renderWithProviders(<ReviewPanel auctionPublicId="00000000-0000-0000-0000-000000000007" isParty />, {
      auth: "authenticated",
      authUser: partyUser,
    });
    expect(
      await screen.findByTestId("review-panel-revealed"),
    ).toBeInTheDocument();
    expect(screen.getAllByTestId("review-card")).toHaveLength(1);
    expect(
      screen.getByTestId("review-panel-window-closed-note"),
    ).toBeInTheDocument();
  });

  it("renders window-closed-none for a party viewer past the window with no reviews", async () => {
    stubEnvelope({
      reviews: [],
      myPendingReview: null,
      canReview: false,
      windowClosesAt: "2020-01-01T00:00:00Z",
    });
    renderWithProviders(<ReviewPanel auctionPublicId="00000000-0000-0000-0000-000000000007" isParty />, {
      auth: "authenticated",
      authUser: partyUser,
    });
    expect(
      await screen.findByTestId("review-panel-window-closed"),
    ).toBeInTheDocument();
  });

  it("renders the read-only fallback for anonymous viewers when there are no reviews", async () => {
    stubEnvelope({
      reviews: [],
      myPendingReview: null,
      canReview: false,
      windowClosesAt: "2020-01-01T00:00:00Z",
    });
    renderWithProviders(<ReviewPanel auctionPublicId="00000000-0000-0000-0000-000000000007" isParty={false} />, {
      auth: "anonymous",
    });
    expect(
      await screen.findByTestId("review-panel-readonly"),
    ).toBeInTheDocument();
  });

  it("refetches the envelope when the query is invalidated", async () => {
    // Simulate the WS-driven invalidation path by flipping the mock
    // response on the second call — the assertion is that the panel
    // re-renders into the new state after invalidation, without any
    // page reload.
    let callCount = 0;
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/reviews", () => {
        callCount += 1;
        if (callCount === 1) {
          return HttpResponse.json({
            reviews: [],
            myPendingReview: makeReview({ visible: false, pending: true }),
            canReview: false,
            windowClosesAt: "2026-05-10T00:00:00Z",
          });
        }
        return HttpResponse.json({
          reviews: [
            makeReview({ publicId: "00000000-0000-0000-0000-000000000001", reviewerPublicId: "00000000-0000-0000-0000-00000000002a" }),
            makeReview({ publicId: "00000000-0000-0000-0000-000000000002", reviewerPublicId: "00000000-0000-0000-0000-00000000004d" }),
          ],
          myPendingReview: null,
          canReview: false,
          windowClosesAt: "2026-05-10T00:00:00Z",
        });
      }),
    );
    const { queryClient } = renderWithInspectableClient(
      <ReviewPanel auctionPublicId="00000000-0000-0000-0000-000000000007" isParty />,
      partyUser,
    );
    await screen.findByTestId("review-panel-pending");
    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: ["reviews", "auction", "00000000-0000-0000-0000-000000000007"],
      });
    });
    await waitFor(() => {
      expect(screen.getByTestId("review-panel-revealed")).toBeInTheDocument();
    });
  });
});

// --------------------------------------------------------------------------
// Helper — render with an accessible QueryClient handle so the test can
// drive invalidations directly (simulating the WS handler's behaviour).
// Mirrors the internals of {@code renderWithProviders} so the test can
// reach the QueryClient without widening the shared helper's public API.
// --------------------------------------------------------------------------

function renderWithInspectableClient(
  ui: ReactElement,
  authUser: AuthUser,
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  queryClient.setQueryData(["auth", "session"], authUser);

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <ThemeProvider
        attribute="class"
        defaultTheme="light"
        enableSystem={false}
      >
        <QueryClientProvider client={queryClient}>
          <ToastProvider>{children}</ToastProvider>
        </QueryClientProvider>
      </ThemeProvider>
    );
  }

  render(ui, { wrapper: Wrapper });
  return { queryClient };
}
