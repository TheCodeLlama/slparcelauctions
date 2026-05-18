import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { adminEscrowReviewsHandlers } from "@/test/msw/handlers";
import type { AdminEscrowReviewDetail } from "@/lib/admin/escrowReviews";

// next/navigation is globally mocked in vitest.setup.ts, but that mock hands
// back a fresh vi.fn() for `push` on every render so it can't be asserted on.
// Override with a stable `push` spy (same pattern as RegisterForm.test.tsx /
// AdminGroupReportDetailPage.test.tsx) so the resolve success side-effect —
// router.push("/admin/escrow-reviews") — is observable.
const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push,
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/admin/escrow-reviews/00000000-0000-0000-0000-000000000001",
  useSearchParams: () => new URLSearchParams(),
}));

import { AdminEscrowReviewDetailPage } from "./AdminEscrowReviewDetailPage";

const REVIEW_ID = "00000000-0000-0000-0000-000000000001";

const baseDetail: AdminEscrowReviewDetail = {
  reviewPublicId: REVIEW_ID,
  escrowPublicId: "00000000-0000-0000-0000-0000000000e1",
  auctionPublicId: "00000000-0000-0000-0000-0000000000a1",
  auctionTitle: "Beachfront 1024m²",
  parcelName: "Beachfront",
  slurl: "https://maps.secondlife.com/secondlife/Test%20Region/128/128/0",
  step: "SET_SELL_TO",
  reason: "USER_REQUESTED",
  status: "OPEN",
  requestedRole: "SELLER",
  resolution: null,
  adminNotes: null,
  createdAt: "2026-05-17T14:22:00Z",
  resolvedAt: null,
  escrowState: "TRANSFER_PENDING",
  finalBidAmount: 1031,
  fundedAt: "2026-05-17T13:00:00Z",
  sellToConfirmedAt: null,
  transferConfirmedAt: null,
  transferDeadline: "2026-05-20T13:00:00Z",
  sellToLastResult: "FOR_SALE_BUT_NOT_TO_WINNER",
  sellToLastCheckedAt: "2026-05-17T14:00:00Z",
  sellToVerifyAttempts: 3,
  buyVerifySellerAttempts: 0,
  buyVerifyBuyerAttempts: 0,
  consecutiveSellToBotFailures: 0,
  consecutiveWorldApiFailures: 0,
};

describe("AdminEscrowReviewDetailPage", () => {
  it("renders the review header and parcel SLURL link", async () => {
    server.use(adminEscrowReviewsHandlers.detail(REVIEW_ID, baseDetail));
    renderWithProviders(
      <AdminEscrowReviewDetailPage reviewPublicId={REVIEW_ID} />,
    );
    expect(
      await screen.findByRole("heading", { name: "Beachfront 1024m²" }),
    ).toBeInTheDocument();
    const link = screen.getByRole("link", {
      name: /View parcel in Second Life/i,
    });
    expect(link).toHaveAttribute("href", baseDetail.slurl);
  });

  it("surfaces the observed sell-to last result evidence", async () => {
    server.use(adminEscrowReviewsHandlers.detail(REVIEW_ID, baseDetail));
    renderWithProviders(
      <AdminEscrowReviewDetailPage reviewPublicId={REVIEW_ID} />,
    );
    expect(
      await screen.findByText("FOR_SALE_BUT_NOT_TO_WINNER"),
    ).toBeInTheDocument();
  });

  it("renders all four resolution actions", async () => {
    server.use(adminEscrowReviewsHandlers.detail(REVIEW_ID, baseDetail));
    renderWithProviders(
      <AdminEscrowReviewDetailPage reviewPublicId={REVIEW_ID} />,
    );
    expect(
      await screen.findByLabelText(/Force confirm Sell To/),
    ).toBeInTheDocument();
    expect(screen.getByLabelText(/Force complete transfer/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Refund winner/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Dismiss/)).toBeInTheDocument();
  });

  it("disables apply button until the admin note is non-empty", async () => {
    server.use(adminEscrowReviewsHandlers.detail(REVIEW_ID, baseDetail));
    renderWithProviders(
      <AdminEscrowReviewDetailPage reviewPublicId={REVIEW_ID} />,
    );
    const button = await screen.findByRole("button", {
      name: /apply resolution/i,
    });
    expect(button).toBeDisabled();

    const textarea = screen.getByPlaceholderText(/What did you verify/i);
    await userEvent.type(textarea, "Verified in-world by admin");
    expect(button).toBeEnabled();
  });

  it("shows a read-only resolution panel for resolved reviews", async () => {
    server.use(
      adminEscrowReviewsHandlers.detail(REVIEW_ID, {
        ...baseDetail,
        status: "RESOLVED",
        resolution: "FORCE_CONFIRM_SELL_TO",
        adminNotes: "Confirmed Sell To manually",
        resolvedAt: "2026-05-17T15:00:00Z",
      }),
    );
    renderWithProviders(
      <AdminEscrowReviewDetailPage reviewPublicId={REVIEW_ID} />,
    );
    expect(
      await screen.findByText(/This review has been resolved/i),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /apply resolution/i }),
    ).not.toBeInTheDocument();
  });

  it("resolve happy path posts the action + note and routes back to the queue", async () => {
    let resolvePayload: Record<string, unknown> | null = null;
    server.use(
      adminEscrowReviewsHandlers.detail(REVIEW_ID, baseDetail),
      http.post(
        `*/api/v1/admin/escrow-reviews/${REVIEW_ID}/resolve`,
        async ({ request }) => {
          resolvePayload = (await request.json()) as Record<string, unknown>;
          return HttpResponse.json({
            reviewPublicId: REVIEW_ID,
            newStatus: "RESOLVED",
            resolution: "FORCE_CONFIRM_SELL_TO",
            resolvedAt: "2026-05-17T15:00:00Z",
          });
        },
      ),
    );
    renderWithProviders(
      <AdminEscrowReviewDetailPage reviewPublicId={REVIEW_ID} />,
    );

    await userEvent.click(
      await screen.findByLabelText(/Force confirm Sell To/),
    );
    await userEvent.type(
      screen.getByPlaceholderText(/What did you verify/i),
      "Verified Sell To in-world; advancing escrow.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: /apply resolution/i }),
    );

    await waitFor(() => expect(resolvePayload).not.toBeNull());
    expect(resolvePayload).toEqual({
      action: "FORCE_CONFIRM_SELL_TO",
      adminNote: "Verified Sell To in-world; advancing escrow.",
    });
    await waitFor(() =>
      expect(push).toHaveBeenCalledWith("/admin/escrow-reviews"),
    );
  });
});
