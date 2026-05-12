import Link from "next/link";
import { Star, StarHalf } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import type { GroupRating } from "@/types/realty";

export interface GroupRatingBadgeProps {
  /**
   * Aggregated rating from the backend. {@code null} (or a populated row
   * with {@code reviewCount === 0} / {@code averageRating === null})
   * collapses to the "No reviews yet" empty state.
   */
  rating: GroupRating | null;
  /**
   * Stopgap link target: a dedicated group-reviews page is deferred (see
   * {@code DEFERRED_WORK.md} — Sub-project F). Until that lands, clicking
   * the badge routes to the group leader's existing user-reviews page so
   * users can at least see one signal of the leader's reputation. Omit to
   * render a non-link span.
   */
  leaderPublicId?: string;
  className?: string;
}

/**
 * Render five star icons (filled / half / empty) reflecting a numeric
 * rating in {@code [0, 5]}. Uses Lucide's {@code Star} and {@code StarHalf}
 * — coarser than {@link RatingSummary}'s partial-fill SVG gradient but
 * good enough for a compact inline badge.
 */
function StarRow({ rating }: { rating: number }) {
  const clamped = Math.max(0, Math.min(5, rating));
  const fullStars = Math.floor(clamped);
  const remainder = clamped - fullStars;
  // Half-star threshold matches common UX: render a half when the fractional
  // part falls within [0.25, 0.75); above that round up to a full star.
  const hasHalf = remainder >= 0.25 && remainder < 0.75;
  const fullCount = remainder >= 0.75 ? fullStars + 1 : fullStars;
  const emptyCount = 5 - fullCount - (hasHalf ? 1 : 0);

  return (
    <span className="inline-flex items-center gap-0.5 text-brand" aria-hidden="true">
      {Array.from({ length: fullCount }, (_, i) => (
        <Star key={`f-${i}`} className="size-3.5 fill-current" />
      ))}
      {hasHalf && <StarHalf className="size-3.5 fill-current" />}
      {Array.from({ length: emptyCount }, (_, i) => (
        <Star key={`e-${i}`} className="size-3.5 text-fg-muted/40" />
      ))}
    </span>
  );
}

/**
 * Compact rating badge for a realty group: row of stars + numeric average
 * + review count, e.g. "★★★★☆ 4.2 (12 reviews)". Renders inside group
 * profile headers and admin listing rows.
 *
 * <p>Empty state ({@code rating === null} or {@code reviewCount === 0} or
 * {@code averageRating === null}) collapses to the muted "No reviews yet"
 * line so the badge can be mounted unconditionally without spec'ing every
 * caller around the empty case.
 *
 * <p>Clicking the badge follows the stopgap leader-reviews link when
 * {@code leaderPublicId} is supplied; otherwise renders as a non-link span.
 * A dedicated {@code /groups/{slug}/reviews} page is on the deferred list.
 */
export function GroupRatingBadge({
  rating,
  leaderPublicId,
  className,
}: GroupRatingBadgeProps) {
  if (
    rating === null ||
    rating.reviewCount === 0 ||
    rating.averageRating === null
  ) {
    return (
      <span
        className={cn("text-xs text-fg-muted", className)}
        data-testid="group-rating-badge"
        data-variant="empty"
      >
        No reviews yet
      </span>
    );
  }

  const { averageRating, reviewCount } = rating;
  const avg = Number(averageRating);
  const formatted = avg.toFixed(1);
  const countLabel = `(${reviewCount} review${reviewCount === 1 ? "" : "s"})`;
  const ariaLabel = `${formatted} out of 5 stars, ${reviewCount} review${reviewCount === 1 ? "" : "s"}`;

  const inner = (
    <>
      <StarRow rating={avg} />
      <span className="text-xs font-semibold text-fg">{formatted}</span>
      <span className="text-xs text-fg-muted">{countLabel}</span>
    </>
  );

  const baseClasses = "inline-flex items-center gap-1.5";

  if (leaderPublicId) {
    return (
      <Link
        href={`/users/${encodeURIComponent(leaderPublicId)}/reviews`}
        className={cn(baseClasses, "hover:underline", className)}
        data-testid="group-rating-badge"
        data-variant="populated"
        aria-label={ariaLabel}
      >
        {inner}
      </Link>
    );
  }

  return (
    <span
      className={cn(baseClasses, className)}
      data-testid="group-rating-badge"
      data-variant="populated"
      aria-label={ariaLabel}
    >
      {inner}
    </span>
  );
}
