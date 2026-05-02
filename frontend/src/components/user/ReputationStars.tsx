import { RatingSummary } from "@/components/reviews/RatingSummary";

/**
 * Backwards-compat wrapper around the new {@link RatingSummary} primitive
 * (Epic 08 sub-spec 1 §8.3). Existing callsites — public profile,
 * listing-card seller strip — pass their existing props here and the
 * partial-star renderer takes care of the rest.
 *
 * A {@code label} is the only feature this wrapper adds on top of
 * {@code RatingSummary}: the section-header strip above the stars is a
 * profile-view convention that isn't worth polluting the primitive with.
 */
export interface ReputationStarsProps {
  /**
   * Numeric rating. BigDecimal serializes as a string on the wire in some
   * legacy endpoints — the component normalizes before forwarding so
   * callers can pass either shape.
   */
  rating: number | string | null;
  reviewCount: number;
  label?: string;
}

export function ReputationStars({
  rating,
  reviewCount,
  label,
}: ReputationStarsProps) {
  const normalized =
    typeof rating === "string" ? Number.parseFloat(rating) : rating;
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <span className="text-[11px] font-medium font-bold uppercase tracking-widest text-fg-muted">
          {label}
        </span>
      )}
      <RatingSummary rating={normalized} reviewCount={reviewCount} size="md" />
    </div>
  );
}
