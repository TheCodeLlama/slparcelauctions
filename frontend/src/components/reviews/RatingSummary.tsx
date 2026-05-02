import { useId } from "react";
import { cn } from "@/lib/cn";

/**
 * Three display sizes for {@link RatingSummary}. The {@code star} / {@code text}
 * / {@code count} slots map to Tailwind typography + sizing tokens so every
 * caller picks a consistent pair — a tiny listing-card rating stays readable
 * and the hero rating stays prominent without ad-hoc class overrides.
 */
type Size = "sm" | "md" | "lg";

const SIZE_MAP: Record<
  Size,
  { star: string; text: string; count: string }
> = {
  sm: { star: "size-3.5", text: "text-xs font-medium", count: "text-[11px] font-medium" },
  md: { star: "size-4", text: "text-sm font-semibold tracking-tight font-bold", count: "text-xs" },
  lg: { star: "size-5", text: "text-base font-bold tracking-tight font-bold", count: "text-sm" },
};

export interface RatingSummaryProps {
  /** The average rating in [0, 5]. {@code null} → "No ratings yet". */
  rating: number | null;
  /** Number of reviews behind the average. 0 → "No ratings yet". */
  reviewCount: number;
  size?: Size;
  /**
   * When {@code true}, omits the "(12 reviews)" suffix. Used inside
   * {@link ReviewCard} where the single-review rating gets a date stamp
   * instead of a count.
   */
  hideCountText?: boolean;
  className?: string;
}

interface PartialStarProps {
  /**
   * How full the star should draw, clamped to {@code [0, 1]}. Values
   * between drive a {@code <linearGradient>} whose two stops sit at
   * {@code fillRatio * 100%} to produce a sharp left-to-right split.
   */
  fillRatio: number;
  className?: string;
  /**
   * Gradient id. Must be unique per star instance — multiple stars on one
   * page with a duplicate id collapse to the first gradient's fill. Caller
   * supplies a deterministic prefix (via {@code useId()}) plus the star
   * index.
   */
  gradientId: string;
}

/**
 * Star SVG with a two-stop linear gradient driving the partial fill. The
 * filled portion uses {@code --color-primary}; the empty portion uses
 * {@code --color-surface-variant}. Design-system tokens only — no dark
 * variants because both theme tokens adapt automatically.
 */
function PartialStar({ fillRatio, className, gradientId }: PartialStarProps) {
  const clamped = Math.max(0, Math.min(1, fillRatio));
  const pct = clamped * 100;
  const offset = `${pct}%`;
  return (
    <svg
      viewBox="0 0 20 20"
      aria-hidden="true"
      className={className}
      data-testid="rating-star"
      data-fill={clamped.toFixed(2)}
    >
      <defs>
        <linearGradient id={gradientId}>
          <stop
            offset={offset}
            style={{ stopColor: "var(--color-primary)" }}
          />
          <stop
            offset={offset}
            style={{ stopColor: "var(--color-surface-variant)" }}
          />
        </linearGradient>
      </defs>
      <path
        d="M10 1l2.78 5.64 6.22.9-4.5 4.39 1.06 6.2L10 15.27l-5.56 2.86 1.06-6.2L1 7.54l6.22-.9L10 1z"
        fill={`url(#${gradientId})`}
        stroke="currentColor"
        strokeWidth="0.5"
        className="text-brand"
      />
    </svg>
  );
}

/**
 * Compute the partial-star fill ratios for every position given a numeric
 * rating. Exported for test coverage of the math across boundary values
 * (0, 2.5, 4.2, 5.0) without having to scrape the DOM.
 */
export function starFillRatios(rating: number): number[] {
  return [0, 1, 2, 3, 4].map((i) => Math.max(0, Math.min(1, rating - i)));
}

/**
 * Five-star rating display with partial fills. Empty state ({@code rating
 * === null} or {@code reviewCount === 0}) collapses to "No ratings yet".
 * Uses {@link useId} for the gradient-id prefix so duplicate mounts on the
 * same page don't collide — each {@link PartialStar} suffixes the shared
 * prefix with its index.
 */
export function RatingSummary({
  rating,
  reviewCount,
  size = "md",
  hideCountText,
  className,
}: RatingSummaryProps) {
  const reactId = useId();
  const sz = SIZE_MAP[size];

  if (rating === null || rating <= 0 || reviewCount === 0) {
    return (
      <span
        className={cn(sz.count, "text-fg-muted", className)}
        data-testid="rating-empty"
      >
        No ratings yet
      </span>
    );
  }

  const numeric = Number(rating);
  const ratios = starFillRatios(numeric);
  return (
    <div
      className={cn("flex items-center gap-2", className)}
      role="img"
      aria-label={`${numeric.toFixed(1)} out of 5 stars, ${reviewCount} review${reviewCount === 1 ? "" : "s"}`}
    >
      <div className="flex gap-0.5">
        {ratios.map((fill, i) => (
          <PartialStar
            key={i}
            fillRatio={fill}
            className={sz.star}
            gradientId={`${reactId}-star-${i}`}
          />
        ))}
      </div>
      <span className={sz.text}>{numeric.toFixed(1)}</span>
      {!hideCountText && (
        <span className={cn(sz.count, "text-fg-muted")}>
          ({reviewCount} review{reviewCount === 1 ? "" : "s"})
        </span>
      )}
    </div>
  );
}
