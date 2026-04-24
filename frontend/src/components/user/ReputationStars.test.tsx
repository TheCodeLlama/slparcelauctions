import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { ReputationStars } from "./ReputationStars";

describe("ReputationStars", () => {
  it("shows 'No ratings yet' when rating is null", () => {
    renderWithProviders(
      <ReputationStars rating={null} reviewCount={0} label="Seller" />,
    );

    expect(screen.getByText("No ratings yet")).toBeInTheDocument();
    expect(screen.getByText("Seller")).toBeInTheDocument();
  });

  it("shows numeric rating with plural reviews", () => {
    renderWithProviders(
      <ReputationStars rating={4.7} reviewCount={12} />,
    );

    expect(screen.getByText("4.7")).toBeInTheDocument();
    expect(screen.getByText("(12 reviews)")).toBeInTheDocument();
  });

  it("shows singular 'review' for reviewCount of 1", () => {
    renderWithProviders(
      <ReputationStars rating={5.0} reviewCount={1} />,
    );

    expect(screen.getByText("5.0")).toBeInTheDocument();
    expect(screen.getByText("(1 review)")).toBeInTheDocument();
  });

  it("accepts a string rating (BigDecimal wire shape) and parses it", () => {
    renderWithProviders(<ReputationStars rating="4.3" reviewCount={5} />);
    expect(screen.getByText("4.3")).toBeInTheDocument();
    // Five stars rendered by the underlying RatingSummary primitive.
    expect(screen.getAllByTestId("rating-star")).toHaveLength(5);
  });

  it("delegates to RatingSummary's partial-star rendering", () => {
    renderWithProviders(<ReputationStars rating={3.5} reviewCount={2} />);
    // 3.5 → [1, 1, 1, 0.5, 0]
    const stars = screen.getAllByTestId("rating-star");
    expect(stars[2].getAttribute("data-fill")).toBe("1.00");
    expect(stars[3].getAttribute("data-fill")).toBe("0.50");
    expect(stars[4].getAttribute("data-fill")).toBe("0.00");
  });
});
