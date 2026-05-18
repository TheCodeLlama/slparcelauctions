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

  it("omits the numeric label when hideNumber is set", () => {
    // Used by the realty review rows, which put the rating next to a
    // "· <date>" stamp instead of the numeric label.
    renderWithProviders(
      <RatingSummary rating={4.0} reviewCount={3} hideNumber hideCountText />,
    );
    expect(screen.getAllByTestId("rating-star")).toHaveLength(5);
    expect(screen.queryByText("4.0")).not.toBeInTheDocument();
  });

  it("supports the compact xs size for realty rows", () => {
    renderWithProviders(
      <RatingSummary rating={4.0} reviewCount={2} size="xs" hideCountText />,
    );
    expect(screen.getAllByTestId("rating-star")).toHaveLength(5);
  });

  it("produces the correct per-star fill ratios across rating values", () => {
    expect(starFillRatios(0)).toEqual([0, 0, 0, 0, 0]);
    expect(starFillRatios(2.5)).toEqual([1, 1, 0.5, 0, 0]);
    expect(starFillRatios(5.0)).toEqual([1, 1, 1, 1, 1]);
    // Quantization to 1/10th removes the floating-point subtraction drift
    // the old implementation had — 4.2 now yields an exact 0.2.
    expect(starFillRatios(4.2)).toEqual([1, 1, 1, 1, 0.2]);
  });

  it("quantizes the fill ratios to the nearest 1/10th", () => {
    // 4.27 → 4.3 worth of fill, so the 5th star is exactly 0.3 (not 0.27).
    expect(starFillRatios(4.27)).toEqual([1, 1, 1, 1, 0.3]);
    // 4.0 → four full stars and one empty (NOT five).
    expect(starFillRatios(4.0)).toEqual([1, 1, 1, 1, 0]);
    // 2.55 → 2.6 quantized → third star 0.6.
    expect(starFillRatios(2.55)).toEqual([1, 1, 0.6, 0, 0]);
  });

  it("quantizes the displayed numeric label to the nearest 1/10th", () => {
    renderWithProviders(<RatingSummary rating={4.27} reviewCount={9} />);
    expect(screen.getByText("4.3")).toBeInTheDocument();
    expect(screen.queryByText("4.2")).not.toBeInTheDocument();
  });

  it("keeps the label and visual fill in agreement for an exact integer", () => {
    renderWithProviders(<RatingSummary rating={4.0} reviewCount={5} />);
    expect(screen.getByText("4.0")).toBeInTheDocument();
    const stars = screen.getAllByTestId("rating-star");
    expect(stars.map((s) => s.getAttribute("data-fill"))).toEqual([
      "1.00",
      "1.00",
      "1.00",
      "1.00",
      "0.00",
    ]);
  });

  it("quantizes 2.55 to a 2.6 label", () => {
    renderWithProviders(<RatingSummary rating={2.55} reviewCount={4} />);
    expect(screen.getByText("2.6")).toBeInTheDocument();
  });

  it("exposes the fill ratio via data-fill for partial stars", () => {
    renderWithProviders(<RatingSummary rating={2.5} reviewCount={3} />);
    const stars = screen.getAllByTestId("rating-star");
    expect(stars[0].getAttribute("data-fill")).toBe("1.00");
    expect(stars[2].getAttribute("data-fill")).toBe("0.50");
    expect(stars[4].getAttribute("data-fill")).toBe("0.00");
  });

  it("paints the gradient with the real brand / fg-subtle design tokens", () => {
    const { container } = renderWithProviders(
      <RatingSummary rating={3.5} reviewCount={2} />,
    );
    const html = container.innerHTML;
    // The filled portion uses the real --color-brand token and the empty
    // portion uses --color-fg-subtle (both defined + dark-mode-aware in
    // globals.css). The old dead tokens rendered every star solid black.
    expect(html).toMatch(/var\(--color-brand\)/);
    expect(html).toMatch(/var\(--color-fg-subtle\)/);
    expect(html).not.toMatch(/--color-primary/);
    expect(html).not.toMatch(/--color-surface-variant/);
    // Design-system tokens only — no hex literals.
    expect(html).not.toMatch(/#[0-9a-fA-F]{3,6}/);
  });

  it("renders in dark mode with the real tokens and no hardcoded colors", () => {
    const { container } = renderWithProviders(
      <RatingSummary rating={3.5} reviewCount={2} />,
      { theme: "dark", forceTheme: true },
    );
    const html = container.innerHTML;
    expect(html).toMatch(/var\(--color-brand\)/);
    expect(html).toMatch(/var\(--color-fg-subtle\)/);
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
