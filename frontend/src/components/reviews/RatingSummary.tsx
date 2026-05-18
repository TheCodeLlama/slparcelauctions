import { useId } from "react";
import { cn } from "@/lib/cn";

/**
 * Display sizes for {@link RatingSummary}. The {@code star} / {@code text}
 * / {@code count} slots map to Tailwind typography + sizing tokens so every
 * caller picks a consistent pair — a tiny realty-row rating stays readable
 * and the hero rating stays prominent without ad-hoc class overrides.
 *
 * {@code xs} matches the old realty {@code <StarRating size={11}>} compact
 * look so the member / review rows survive the consolidation visually.
 */
type Size = "xs" | "sm" | "md" | "lg";

const SIZE_MAP: Record<
  Size,
  { star: string; text: string; count: string }
> = {
  xs: { star: "size-3", text: "text-xs text-fg-muted font-medium", count: "text-[11px] font-medium" },
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
  /**
   * When {@code true}, omits the numeric label entirely. Used by the realty
   * review rows, which pair the stars with a "· <date>" stamp instead of
   * the numeric average.
   */
  hideNumber?: boolean;
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
 * filled portion uses {@code --color-brand}; the empty portion uses
 * {@code --color-fg-subtle}. Both are real design-system tokens defined in
 * {@code globals.css} and are dark-mode-aware, so no dark variants are
 * needed. (The previous {@code --color-primary} / {@code --color-surface-variant}
 * names were never defined — an undefined {@code var()} with no fallback in
 * an SVG {@code stop-color} is invalid and fell back to solid black, so
 * every star rendered black regardless of rating.)
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
            style={{ stopColor: "var(--color-brand)" }}
          />
          <stop
            offset={offset}
            style={{ stopColor: "var(--color-fg-subtle)" }}
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
 * Quantize an incoming rating to the nearest 1/10th. Done exactly once so
 * the numeric label and the visual star fill always agree (4.27 → 4.3 label
 * AND 4.3 worth of fill; 4.0 → "4.0" and four full + one empty star).
 */
export function quantizeRating(rating: number): number {
  return Math.round(rating * 10) / 10;
}

/**
 * Compute the partial-star fill ratios for every position given a numeric
 * rating. The rating is quantized to the nearest 1/10th first so the fill
 * matches the displayed label exactly and there is no floating-point
 * subtraction drift. Exported for test coverage of the math across boundary
 * values without having to scrape the DOM.
 */
export function starFillRatios(rating: number): number[] {
  const q = quantizeRating(rating);
  return [0, 1, 2, 3, 4].map((i) =>
    Math.round(Math.max(0, Math.min(1, q - i)) * 10) / 10,
  );
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
  hideNumber,
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

  const quantized = quantizeRating(Number(rating));
  const label = quantized.toFixed(1);
  const ratios = starFillRatios(quantized);
  return (
    <div
      className={cn("flex items-center gap-2", className)}
      role="img"
      aria-label={`${label} out of 5 stars, ${reviewCount} review${reviewCount === 1 ? "" : "s"}`}
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
      {!hideNumber && <span className={sz.text}>{label}</span>}
      {!hideCountText && (
        <span className={cn(sz.count, "text-fg-muted")}>
          ({reviewCount} review{reviewCount === 1 ? "" : "s"})
        </span>
      )}
    </div>
  );
}
