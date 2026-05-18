import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { RatingSummary } from "@/components/reviews/RatingSummary";

/**
 * The realty browse rows used to render a bespoke {@code <StarRating>} that
 * did {@code Math.round(value)} over 5 lucide stars (no fractional fill).
 * It was deleted and the three callsites — MemberRow, GroupDetailPage's
 * review list, PublicGroupProfile's review list — now delegate to the
 * shared fractional {@link RatingSummary} primitive.
 *
 * These cover the exact prop combinations the migrated sites pass so a
 * regression in the consolidated config (fractional fill, compact size,
 * number visibility, no "(N reviews)" suffix) fails loudly.
 */
describe("realty StarRating consolidation onto RatingSummary", () => {
  it("MemberRow config: fractional fill + numeric value, no review count", () => {
    // <RatingSummary rating={member.rating} reviewCount={1} size="xs"
    //   hideCountText /> — member rows show the number, no "(N reviews)".
    renderWithProviders(
      <RatingSummary rating={4.27} reviewCount={1} size="xs" hideCountText />,
    );
    const stars = screen.getAllByTestId("rating-star");
    expect(stars).toHaveLength(5);
    // Quantized to 4.3 → 5th star is a real fractional 0.3 fill, not a
    // rounded-up full star.
    expect(stars[4].getAttribute("data-fill")).toBe("0.30");
    expect(screen.getByText("4.3")).toBeInTheDocument();
    expect(screen.queryByText(/review/)).not.toBeInTheDocument();
  });

  it("MemberRow config: a 4.0 rating shows 4 full + 1 empty, not 5 full", () => {
    renderWithProviders(
      <RatingSummary rating={4.0} reviewCount={1} size="xs" hideCountText />,
    );
    const stars = screen.getAllByTestId("rating-star");
    expect(stars.map((s) => s.getAttribute("data-fill"))).toEqual([
      "1.00",
      "1.00",
      "1.00",
      "1.00",
      "0.00",
    ]);
    expect(screen.getByText("4.0")).toBeInTheDocument();
  });

  it("review-row config: fractional fill, NO numeric label, no review count", () => {
    // GroupDetailPage / PublicGroupProfile review rows pass hideNumber so
    // the rating sits next to a "· <date>" stamp instead of the number.
    renderWithProviders(
      <RatingSummary
        rating={3.5}
        reviewCount={1}
        size="xs"
        hideNumber
        hideCountText
      />,
    );
    const stars = screen.getAllByTestId("rating-star");
    expect(stars).toHaveLength(5);
    expect(stars[3].getAttribute("data-fill")).toBe("0.50");
    expect(screen.queryByText("3.5")).not.toBeInTheDocument();
    expect(screen.queryByText(/review/)).not.toBeInTheDocument();
  });

  it("a zero rating collapses to 'No ratings yet' (member with no reviews)", () => {
    renderWithProviders(
      <RatingSummary rating={null} reviewCount={0} size="xs" hideCountText />,
    );
    expect(screen.getByText("No ratings yet")).toBeInTheDocument();
    expect(screen.queryAllByTestId("rating-star")).toHaveLength(0);
  });
});
