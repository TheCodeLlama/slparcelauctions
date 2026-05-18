import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminEscrowReviewsHandlers } from "@/test/msw/handlers";
import { AdminEscrowReviewDetailPage } from "./AdminEscrowReviewDetailPage";
import type { AdminEscrowReviewDetail } from "@/lib/admin/escrowReviews";

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
});
