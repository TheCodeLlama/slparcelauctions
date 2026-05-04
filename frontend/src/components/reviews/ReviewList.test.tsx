import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type { ReviewDto } from "@/types/review";
import { ReviewList } from "./ReviewList";

function makeReview(id: number, overrides: Partial<ReviewDto> = {}): ReviewDto {
  return {
    publicId: `00000000-0000-0000-0000-${String(id).padStart(12, "0")}`,
    auctionPublicId: `00000000-0000-0000-0000-${String(id * 10).padStart(12, "0")}`,
    auctionTitle: `Parcel #${id}`,
    auctionPrimaryPhotoUrl: null,
    reviewerPublicId: `00000000-0000-0000-0000-${String(100 + id).padStart(12, "0")}`,
    reviewerDisplayName: `Reviewer ${id}`,
    reviewerAvatarUrl: null,
    revieweePublicId: "00000000-0000-0000-0000-00000000002a",
    reviewedRole: "SELLER",
    rating: 5,
    text: `Review text ${id}`,
    visible: true,
    pending: false,
    submittedAt: "2026-04-18T12:00:00Z",
    revealedAt: "2026-04-19T12:00:00Z",
    response: null,
    ...overrides,
  };
}

describe("ReviewList", () => {
  it("renders a card per review from the paged response", async () => {
    server.use(
      http.get("*/api/v1/users/:id/reviews", () =>
        HttpResponse.json({
          content: [makeReview(1), makeReview(2), makeReview(3)],
          totalElements: 3,
          totalPages: 1,
          number: 0,
          size: 10,
        }),
      ),
    );

    renderWithProviders(
      <ReviewList userPublicId="00000000-0000-0000-0000-00000000002a" role="SELLER" page={0} onPageChange={() => {}} />,
      { auth: "anonymous" },
    );

    await waitFor(() =>
      expect(screen.getAllByTestId("review-card")).toHaveLength(3),
    );
  });

  it("renders the seller-specific empty state when there are no reviews", async () => {
    server.use(
      http.get("*/api/v1/users/:id/reviews", () =>
        HttpResponse.json({
          content: [],
          totalElements: 0,
          totalPages: 0,
          number: 0,
          size: 10,
        }),
      ),
    );

    renderWithProviders(
      <ReviewList userPublicId="00000000-0000-0000-0000-00000000002a" role="SELLER" page={0} onPageChange={() => {}} />,
      { auth: "anonymous" },
    );

    expect(
      await screen.findByText("No reviews as seller yet"),
    ).toBeInTheDocument();
  });

  it("renders the buyer-specific empty state when there are no reviews", async () => {
    server.use(
      http.get("*/api/v1/users/:id/reviews", () =>
        HttpResponse.json({
          content: [],
          totalElements: 0,
          totalPages: 0,
          number: 0,
          size: 10,
        }),
      ),
    );

    renderWithProviders(
      <ReviewList userPublicId="00000000-0000-0000-0000-00000000002a" role="BUYER" page={0} onPageChange={() => {}} />,
      { auth: "anonymous" },
    );

    expect(
      await screen.findByText("No reviews as buyer yet"),
    ).toBeInTheDocument();
  });

  it("renders pagination when totalPages > 1 and fires onPageChange on click", async () => {
    server.use(
      http.get("*/api/v1/users/:id/reviews", () =>
        HttpResponse.json({
          content: [makeReview(1)],
          totalElements: 25,
          totalPages: 3,
          number: 0,
          size: 10,
        }),
      ),
    );

    const onPageChange = vi.fn();
    renderWithProviders(
      <ReviewList
        userPublicId="00000000-0000-0000-0000-00000000002a"
        role="SELLER"
        page={0}
        onPageChange={onPageChange}
      />,
      { auth: "anonymous" },
    );

    await screen.findByTestId("review-list-seller");
    await userEvent.click(screen.getByRole("button", { name: /page 2/i }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it("omits pagination when only one page of results", async () => {
    server.use(
      http.get("*/api/v1/users/:id/reviews", () =>
        HttpResponse.json({
          content: [makeReview(1)],
          totalElements: 1,
          totalPages: 1,
          number: 0,
          size: 10,
        }),
      ),
    );

    renderWithProviders(
      <ReviewList userPublicId="00000000-0000-0000-0000-00000000002a" role="SELLER" page={0} onPageChange={() => {}} />,
      { auth: "anonymous" },
    );

    await screen.findByTestId("review-list-seller");
    expect(
      screen.queryByRole("navigation", { name: /pagination/i }),
    ).toBeNull();
  });

  it("renders an error state when the request fails", async () => {
    server.use(
      http.get("*/api/v1/users/:id/reviews", () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );

    renderWithProviders(
      <ReviewList userPublicId="00000000-0000-0000-0000-00000000002a" role="SELLER" page={0} onPageChange={() => {}} />,
      { auth: "anonymous" },
    );

    expect(
      await screen.findByText("Could not load reviews"),
    ).toBeInTheDocument();
  });
});
