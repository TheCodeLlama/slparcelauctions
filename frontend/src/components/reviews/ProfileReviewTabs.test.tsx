import { beforeEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type { ReviewDto, ReviewedRole } from "@/types/review";
import { ProfileReviewTabs } from "./ProfileReviewTabs";

// --- next/navigation mock with mutable state -------------------------------
//
// The tab component reads useSearchParams and writes via router.replace. We
// back both with a module-scoped URLSearchParams so tests can seed the
// initial query and assert on the resulting push.

const replaceMock = vi.fn();
let currentParams = new URLSearchParams();

function setParams(init: string) {
  currentParams = new URLSearchParams(init);
}

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn() }),
  usePathname: () => "/users/42",
  useSearchParams: () => currentParams,
}));

function makeReview(id: number, role: ReviewedRole): ReviewDto {
  return {
    publicId: `00000000-0000-0000-0000-${String(id).padStart(12, "0")}`,
    auctionPublicId: `00000000-0000-0000-0000-${String(id * 10).padStart(12, "0")}`,
    auctionTitle: `Parcel #${id}`,
    auctionPrimaryPhotoUrl: null,
    reviewerPublicId: `00000000-0000-0000-0000-${String(100 + id).padStart(12, "0")}`,
    reviewerDisplayName: `Reviewer ${id}`,
    reviewerAvatarUrl: null,
    revieweePublicId: "00000000-0000-0000-0000-00000000002a",
    reviewedRole: role,
    rating: 5,
    text: `Review ${id} for role ${role}`,
    visible: true,
    pending: false,
    submittedAt: "2026-04-18T12:00:00Z",
    revealedAt: "2026-04-19T12:00:00Z",
    response: null,
  };
}

/**
 * Register one handler that branches on the {@code role} query param so the
 * same test can cover both tabs without re-registering mid-test.
 */
function registerReviewHandler(options: {
  sellerReviews?: ReviewDto[];
  buyerReviews?: ReviewDto[];
  sellerTotalPages?: number;
  buyerTotalPages?: number;
}) {
  const {
    sellerReviews = [],
    buyerReviews = [],
    sellerTotalPages = 1,
    buyerTotalPages = 1,
  } = options;
  server.use(
    http.get("*/api/v1/users/:id/reviews", ({ request }) => {
      const url = new URL(request.url);
      const role = url.searchParams.get("role") as ReviewedRole | null;
      const page = Number(url.searchParams.get("page") ?? "0");
      if (role === "BUYER") {
        return HttpResponse.json({
          content: buyerReviews,
          totalElements: buyerReviews.length,
          totalPages: buyerTotalPages,
          number: page,
          size: 10,
        });
      }
      return HttpResponse.json({
        content: sellerReviews,
        totalElements: sellerReviews.length,
        totalPages: sellerTotalPages,
        number: page,
        size: 10,
      });
    }),
  );
}

describe("ProfileReviewTabs", () => {
  beforeEach(() => {
    replaceMock.mockClear();
    setParams("");
  });

  it("renders both tabs and defaults to 'As Seller' active", async () => {
    registerReviewHandler({ sellerReviews: [makeReview(1, "SELLER")] });

    renderWithProviders(
      <ProfileReviewTabs
        userPublicId="00000000-0000-0000-0000-00000000002a"
        avgSellerRating={4.5}
        avgBuyerRating={4.8}
        totalSellerReviews={3}
        totalBuyerReviews={2}
      />,
      { auth: "anonymous" },
    );

    const sellerTab = await screen.findByTestId("profile-review-tab-seller");
    const buyerTab = screen.getByTestId("profile-review-tab-buyer");
    expect(sellerTab).toHaveAttribute("aria-selected", "true");
    expect(buyerTab).toHaveAttribute("aria-selected", "false");
    // Seller review is rendered under the active tab.
    await waitFor(() =>
      expect(screen.getByText("Reviewer 1")).toBeInTheDocument(),
    );
  });

  it("syncs tab selection to the URL via ?tab=buyer", async () => {
    registerReviewHandler({
      sellerReviews: [makeReview(1, "SELLER")],
      buyerReviews: [makeReview(2, "BUYER")],
    });

    renderWithProviders(
      <ProfileReviewTabs
        userPublicId="00000000-0000-0000-0000-00000000002a"
        avgSellerRating={4.5}
        avgBuyerRating={4.8}
        totalSellerReviews={3}
        totalBuyerReviews={2}
      />,
      { auth: "anonymous" },
    );

    const buyerTab = await screen.findByTestId("profile-review-tab-buyer");
    await userEvent.click(buyerTab);

    await waitFor(() => expect(replaceMock).toHaveBeenCalled());
    const last = replaceMock.mock.calls.at(-1)?.[0] as string;
    expect(last).toContain("tab=buyer");
  });

  it("activates the buyer tab from ?tab=buyer initial URL", async () => {
    setParams("tab=buyer");
    registerReviewHandler({
      buyerReviews: [makeReview(7, "BUYER")],
    });

    renderWithProviders(
      <ProfileReviewTabs
        userPublicId="00000000-0000-0000-0000-00000000002a"
        avgSellerRating={4.5}
        avgBuyerRating={4.8}
        totalSellerReviews={3}
        totalBuyerReviews={2}
      />,
      { auth: "anonymous" },
    );

    const buyerTab = await screen.findByTestId("profile-review-tab-buyer");
    expect(buyerTab).toHaveAttribute("aria-selected", "true");
    await waitFor(() =>
      expect(screen.getByText("Reviewer 7")).toBeInTheDocument(),
    );
  });

  it("tracks independent page state per tab via sellerPage/buyerPage URL keys", async () => {
    registerReviewHandler({
      sellerReviews: [makeReview(1, "SELLER")],
      sellerTotalPages: 3,
    });

    renderWithProviders(
      <ProfileReviewTabs
        userPublicId="00000000-0000-0000-0000-00000000002a"
        avgSellerRating={4.5}
        avgBuyerRating={4.8}
        totalSellerReviews={30}
        totalBuyerReviews={0}
      />,
      { auth: "anonymous" },
    );

    await screen.findByText("Reviewer 1");
    // Click page 2 in the seller pagination — should sync sellerPage=1
    await userEvent.click(screen.getByRole("button", { name: /page 2/i }));
    await waitFor(() => expect(replaceMock).toHaveBeenCalled());
    const last = replaceMock.mock.calls.at(-1)?.[0] as string;
    expect(last).toContain("sellerPage=1");
    // The buyerPage key is untouched — switching tabs preserves its state.
    expect(last).not.toContain("buyerPage");
  });

  it("renders an empty-state per role when no reviews exist", async () => {
    registerReviewHandler({
      sellerReviews: [],
      buyerReviews: [],
      sellerTotalPages: 0,
      buyerTotalPages: 0,
    });

    renderWithProviders(
      <ProfileReviewTabs
        userPublicId="00000000-0000-0000-0000-00000000002a"
        avgSellerRating={null}
        avgBuyerRating={null}
        totalSellerReviews={0}
        totalBuyerReviews={0}
      />,
      { auth: "anonymous" },
    );

    expect(
      await screen.findByText("No reviews as seller yet"),
    ).toBeInTheDocument();
  });

  it("surfaces the RatingSummary at the top of each tab", async () => {
    registerReviewHandler({
      sellerReviews: [makeReview(1, "SELLER")],
    });

    renderWithProviders(
      <ProfileReviewTabs
        userPublicId="00000000-0000-0000-0000-00000000002a"
        avgSellerRating={4.7}
        avgBuyerRating={4.8}
        totalSellerReviews={23}
        totalBuyerReviews={10}
      />,
      { auth: "anonymous" },
    );

    // The active seller panel shows the 4.7 / 23 reviews summary.
    expect(
      await screen.findByRole("img", {
        name: /4\.7 out of 5 stars, 23 reviews/i,
      }),
    ).toBeInTheDocument();
  });
});
