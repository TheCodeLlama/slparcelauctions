import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { RatingSummary, starFillRatios } from "./RatingSummary";

describe("RatingSummary", () => {
  it("renders 'No ratings yet' when rating is null", () => {
    renderWithProviders(<RatingSummary rating={null} reviewCount={0} />);
    expect(screen.getByText("No ratings yet")).toBeInTheDocument();
    expect(screen.queryAllByTestId("rating-star")).toHaveLength(0);
  });

  it("renders 'No ratings yet' when reviewCount is 0 even with a positive rating", () => {
    // Guard against the backend briefly returning an AVG but 0 reviews during
    // a race — the empty state is the correct UX.
    renderWithProviders(<RatingSummary rating={4.5} reviewCount={0} />);
    expect(screen.getByText("No ratings yet")).toBeInTheDocument();
  });

  it("renders five stars and the numeric average", () => {
    renderWithProviders(<RatingSummary rating={4.2} reviewCount={12} />);
    expect(screen.getAllByTestId("rating-star")).toHaveLength(5);
    expect(screen.getByText("4.2")).toBeInTheDocument();
    expect(screen.getByText("(12 reviews)")).toBeInTheDocument();
  });

  it("uses singular 'review' when count is 1", () => {
    renderWithProviders(<RatingSummary rating={5.0} reviewCount={1} />);
    expect(screen.getByText("(1 review)")).toBeInTheDocument();
  });

  it("omits the count when hideCountText is set", () => {
    renderWithProviders(
      <RatingSummary rating={5.0} reviewCount={1} hideCountText />,
    );
    expect(screen.queryByText(/review/)).not.toBeInTheDocument();
  });

  it("produces the correct per-star fill ratios across rating values", () => {
    expect(starFillRatios(0)).toEqual([0, 0, 0, 0, 0]);
    expect(starFillRatios(2.5)).toEqual([1, 1, 0.5, 0, 0]);
    expect(starFillRatios(5.0)).toEqual([1, 1, 1, 1, 1]);
    // 4.2 - 4 drifts with floating-point subtraction; assert with tolerance.
    const fourPointTwo = starFillRatios(4.2);
    expect(fourPointTwo.slice(0, 4)).toEqual([1, 1, 1, 1]);
    expect(fourPointTwo[4]).toBeCloseTo(0.2, 10);
  });

  it("exposes the fill ratio via data-fill for partial stars", () => {
    renderWithProviders(<RatingSummary rating={2.5} reviewCount={3} />);
    const stars = screen.getAllByTestId("rating-star");
    expect(stars[0].getAttribute("data-fill")).toBe("1.00");
    expect(stars[2].getAttribute("data-fill")).toBe("0.50");
    expect(stars[4].getAttribute("data-fill")).toBe("0.00");
  });

  it("renders in dark mode without hardcoded colors", () => {
    const { container } = renderWithProviders(
      <RatingSummary rating={3.5} reviewCount={2} />,
      { theme: "dark", forceTheme: true },
    );
    // Gradient stops use CSS var tokens, not hex literals.
    const html = container.innerHTML;
    expect(html).toMatch(/var\(--color-primary\)/);
    expect(html).toMatch(/var\(--color-surface-variant\)/);
    expect(html).not.toMatch(/#[0-9a-fA-F]{3,6}/);
  });

  it("generates unique gradient ids across two summary instances", () => {
    const { container } = renderWithProviders(
      <>
        <RatingSummary rating={4.0} reviewCount={1} />
        <RatingSummary rating={4.0} reviewCount={1} />
      </>,
    );
    const gradients = container.querySelectorAll("linearGradient");
    const ids = Array.from(gradients).map((g) => g.getAttribute("id"));
    expect(new Set(ids).size).toBe(ids.length);
  });
});
