import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { GroupRatingBadge } from "./GroupRatingBadge";

describe("GroupRatingBadge", () => {
  it("renders 'No reviews yet' when rating is null", () => {
    renderWithProviders(<GroupRatingBadge rating={null} />);
    expect(screen.getByText("No reviews yet")).toBeInTheDocument();
  });

  it("renders 'No reviews yet' when reviewCount is 0", () => {
    renderWithProviders(
      <GroupRatingBadge
        rating={{ averageRating: null, reviewCount: 0 }}
      />,
    );
    expect(screen.getByText("No reviews yet")).toBeInTheDocument();
  });

  it("renders 'No reviews yet' when averageRating is null but reviewCount > 0", () => {
    // Defensive: backend may regress; null avg should not crash.
    renderWithProviders(
      <GroupRatingBadge
        rating={{ averageRating: null, reviewCount: 4 }}
      />,
    );
    expect(screen.getByText("No reviews yet")).toBeInTheDocument();
  });

  it("renders the average + review count when populated", () => {
    renderWithProviders(
      <GroupRatingBadge
        rating={{ averageRating: 4.2, reviewCount: 12 }}
      />,
    );
    expect(screen.getByText("4.2")).toBeInTheDocument();
    expect(screen.getByText("(12 reviews)")).toBeInTheDocument();
  });

  it("uses singular 'review' when reviewCount is 1", () => {
    renderWithProviders(
      <GroupRatingBadge
        rating={{ averageRating: 5, reviewCount: 1 }}
      />,
    );
    expect(screen.getByText("(1 review)")).toBeInTheDocument();
  });

  it("renders five star icons when populated", () => {
    const { container } = renderWithProviders(
      <GroupRatingBadge
        rating={{ averageRating: 3.5, reviewCount: 8 }}
      />,
    );
    // Lucide icons render as <svg> elements; we render 5 (3 full + 1 half + 1 empty
    // — but Star+StarHalf are still 5 total svgs).
    const svgs = container.querySelectorAll("svg");
    expect(svgs.length).toBe(5);
  });

  it("links to /groups/{groupSlug}/reviews when groupSlug provided", () => {
    renderWithProviders(
      <GroupRatingBadge
        rating={{ averageRating: 4.2, reviewCount: 12 }}
        groupSlug="group-abc-123"
      />,
    );
    const link = screen.getByTestId("group-rating-badge");
    expect(link.tagName).toBe("A");
    expect(link.getAttribute("href")).toBe(
      "/groups/group-abc-123/reviews",
    );
  });

  it("renders as non-link span when groupSlug is omitted", () => {
    renderWithProviders(
      <GroupRatingBadge
        rating={{ averageRating: 4.2, reviewCount: 12 }}
      />,
    );
    const el = screen.getByTestId("group-rating-badge");
    expect(el.tagName).not.toBe("A");
  });
});
