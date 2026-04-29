import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { mockUser } from "@/test/msw/fixtures";
import type { ReviewDto } from "@/types/review";
import { ReviewCard } from "./ReviewCard";

function makeReview(overrides: Partial<ReviewDto> = {}): ReviewDto {
  return {
    id: 1,
    auctionId: 10,
    auctionTitle: "Aurora Parcel",
    auctionPrimaryPhotoUrl: null,
    reviewerId: 100,
    reviewerDisplayName: "Alice",
    reviewerAvatarUrl: null,
    revieweeId: 200,
    reviewedRole: "SELLER",
    rating: 5,
    text: "Great seller, would buy again.\nTwo newlines.",
    visible: true,
    pending: false,
    submittedAt: "2026-04-18T12:00:00Z",
    revealedAt: "2026-04-19T12:00:00Z",
    response: null,
    ...overrides,
  };
}

describe("ReviewCard", () => {
  it("renders reviewer name, rating stars, and review text", () => {
    renderWithProviders(<ReviewCard review={makeReview()} />, {
      auth: "anonymous",
    });
    expect(screen.getByText("Alice")).toBeInTheDocument();
    expect(screen.getAllByTestId("rating-star")).toHaveLength(5);
    expect(screen.getByTestId("review-card-text").textContent).toContain(
      "Great seller",
    );
  });

  it("renders text with whitespace-pre-wrap so newlines show", () => {
    renderWithProviders(<ReviewCard review={makeReview()} />, {
      auth: "anonymous",
    });
    const text = screen.getByTestId("review-card-text");
    expect(text.className).toMatch(/whitespace-pre-wrap/);
  });

  it("renders an auction link by default and hides it when hideAuctionLink is set", () => {
    const { rerender } = renderWithProviders(
      <ReviewCard review={makeReview()} />,
      { auth: "anonymous" },
    );
    const link = screen.getByTestId("review-card-auction-link");
    expect(link).toHaveAttribute("href", "/auction/10");
    expect(link.textContent).toContain("Aurora Parcel");
    rerender(<ReviewCard review={makeReview()} hideAuctionLink />);
    expect(
      screen.queryByTestId("review-card-auction-link"),
    ).not.toBeInTheDocument();
  });

  it("hides the flag button for the review's author", () => {
    // mockUser.id === 100, which matches reviewerId. The author can't flag
    // themselves.
    renderWithProviders(
      <ReviewCard review={makeReview({ reviewerId: mockUser.id })} />,
      { auth: "authenticated" },
    );
    expect(screen.queryByTestId("review-card-flag")).not.toBeInTheDocument();
  });

  it("shows the flag button for non-authors", () => {
    renderWithProviders(
      <ReviewCard review={makeReview({ reviewerId: 999, revieweeId: 888 })} />,
      { auth: "authenticated" },
    );
    expect(screen.getByTestId("review-card-flag")).toBeInTheDocument();
  });

  it("hides the flag button for anonymous viewers", () => {
    renderWithProviders(<ReviewCard review={makeReview()} />, {
      auth: "anonymous",
    });
    expect(screen.queryByTestId("review-card-flag")).not.toBeInTheDocument();
  });

  it("shows the respond button only when viewer is reviewee AND no response exists", () => {
    // Viewer is the reviewee (reviewedRole=SELLER so viewer is the seller)
    // and no response posted yet → button shows.
    const reviewee = makeReview({ revieweeId: mockUser.id, response: null });
    renderWithProviders(<ReviewCard review={reviewee} />, {
      auth: "authenticated",
    });
    expect(screen.getByTestId("review-card-respond")).toBeInTheDocument();
  });

  it("hides the respond button when a response already exists", () => {
    const reviewed = makeReview({
      revieweeId: mockUser.id,
      response: {
        id: 99,
        text: "Thanks",
        createdAt: "2026-04-19T13:00:00Z",
      },
    });
    renderWithProviders(<ReviewCard review={reviewed} />, {
      auth: "authenticated",
    });
    expect(
      screen.queryByTestId("review-card-respond"),
    ).not.toBeInTheDocument();
  });

  it("renders the response section with 'Seller response' label for SELLER reviews", () => {
    const r = makeReview({
      reviewedRole: "SELLER",
      response: {
        id: 99,
        text: "Thanks, enjoy the parcel!",
        createdAt: "2026-04-19T13:00:00Z",
      },
    });
    renderWithProviders(<ReviewCard review={r} />, { auth: "anonymous" });
    const response = screen.getByTestId("review-card-response");
    expect(response.textContent).toContain("Seller response");
    expect(response.textContent).toContain("Thanks, enjoy the parcel!");
  });

  it("labels the response as 'Buyer response' for BUYER reviews", () => {
    const r = makeReview({
      reviewedRole: "BUYER",
      response: {
        id: 99,
        text: "Cheers",
        createdAt: "2026-04-19T13:00:00Z",
      },
    });
    renderWithProviders(<ReviewCard review={r} />, { auth: "anonymous" });
    expect(screen.getByTestId("review-card-response").textContent).toContain(
      "Buyer response",
    );
  });

  it("renders an absolute timestamp in the time tag's title attribute", () => {
    renderWithProviders(<ReviewCard review={makeReview()} />, {
      auth: "anonymous",
    });
    const time = screen.getByText(
      (_, el) => el?.tagName === "TIME" && el.getAttribute("datetime") === "2026-04-18T12:00:00Z",
    );
    expect(time).toHaveAttribute("title");
    expect(time.getAttribute("title")?.length).toBeGreaterThan(0);
  });

  it("renders in dark mode without hardcoded hex colors", () => {
    const { container } = renderWithProviders(
      <ReviewCard review={makeReview()} />,
      { theme: "dark", forceTheme: true, auth: "anonymous" },
    );
    expect(container.innerHTML).not.toMatch(/#[0-9a-fA-F]{6}/);
  });
});
