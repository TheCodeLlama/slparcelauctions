"use client";

import {
  useCallback,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
} from "react";
import { cn } from "@/lib/cn";

/**
 * Interactive 1-5 rating picker. Each star is a {@code role="radio"} inside
 * a {@code role="radiogroup"} container. The component is controlled —
 * caller owns {@code value}, we fire {@code onChange} on click, number-key,
 * Home/End, and ArrowLeft/Right. Hover preview is local state and does not
 * reach {@code onChange}, so mousing in and out without clicking leaves
 * {@code value} untouched (spec §8.7 "Clear affordance: selected rating
 * stays visible after mouseleave").
 *
 * The gradient-free solid star approach matches what the spec asks for —
 * the rating in a submit form is always an integer 1-5, so we flip between
 * fully-filled (selected or hovered) and fully-empty (unselected) with
 * {@code text-primary} and {@code text-surface-variant} tokens rather than
 * reusing {@link RatingSummary}'s {@code <linearGradient>} machinery.
 */

export interface StarSelectorProps {
  /** Current rating in {@code [1, 5]}. {@code null} means nothing picked yet. */
  value: number | null;
  onChange: (value: number) => void;
  /** Accessible label for the whole radiogroup. Required for SR users. */
  label?: string;
  disabled?: boolean;
  size?: "md" | "lg";
  className?: string;
}

const SIZE_CLASS: Record<NonNullable<StarSelectorProps["size"]>, string> = {
  md: "size-7",
  lg: "size-8",
};

// Solid-star path — identical geometry to RatingSummary so both readouts
// line up visually. We draw it once; the surrounding button picks the fill
// color based on hover/selected state.
const STAR_PATH =
  "M10 1l2.78 5.64 6.22.9-4.5 4.39 1.06 6.2L10 15.27l-5.56 2.86 1.06-6.2L1 7.54l6.22-.9L10 1z";

function wrap(index: number): number {
  // Wrap 0 → 5 and 6 → 1 so ArrowLeft on the first star jumps to 5 and
  // ArrowRight on the fifth star jumps to 1 (spec §8.7).
  if (index < 1) return 5;
  if (index > 5) return 1;
  return index;
}

export function StarSelector({
  value,
  onChange,
  label = "Rating",
  disabled,
  size = "lg",
  className,
}: StarSelectorProps) {
  const groupId = useId();
  const [hover, setHover] = useState<number | null>(null);
  const buttonsRef = useRef<Array<HTMLButtonElement | null>>([]);

  const focusStar = useCallback((idx: number) => {
    const btn = buttonsRef.current[idx - 1];
    btn?.focus();
  }, []);

  const select = useCallback(
    (next: number) => {
      if (disabled) return;
      onChange(next);
      focusStar(next);
    },
    [disabled, onChange, focusStar],
  );

  const handleKey = useCallback(
    (e: KeyboardEvent<HTMLButtonElement>, current: number) => {
      if (disabled) return;
      // Number keys 1-5 jump directly — matches spec §8.7.
      if (/^[1-5]$/.test(e.key)) {
        e.preventDefault();
        select(Number.parseInt(e.key, 10));
        return;
      }
      switch (e.key) {
        case "ArrowLeft":
        case "ArrowDown":
          e.preventDefault();
          select(wrap(current - 1));
          break;
        case "ArrowRight":
        case "ArrowUp":
          e.preventDefault();
          select(wrap(current + 1));
          break;
        case "Home":
          e.preventDefault();
          select(1);
          break;
        case "End":
          e.preventDefault();
          select(5);
          break;
        default:
          break;
      }
    },
    [disabled, select],
  );

  const onStarClick = (starValue: number) => (e: MouseEvent) => {
    e.preventDefault();
    select(starValue);
  };

  const active = hover ?? value ?? 0;

  return (
    <div
      role="radiogroup"
      aria-label={label}
      aria-disabled={disabled || undefined}
      id={groupId}
      className={cn("inline-flex items-center gap-1", className)}
      data-testid="star-selector"
      onMouseLeave={() => setHover(null)}
    >
      {[1, 2, 3, 4, 5].map((starValue) => {
        const filled = starValue <= active;
        const checked = value === starValue;
        // Exactly one star in the group is tab-stop: the selected one, or
        // the first when nothing is selected yet. Matches the WAI-ARIA
        // radiogroup pattern where arrow keys cycle focus inside.
        const isTabStop = checked || (value === null && starValue === 1);
        return (
          <button
            key={starValue}
            type="button"
            ref={(el) => {
              buttonsRef.current[starValue - 1] = el;
            }}
            role="radio"
            aria-checked={checked}
            aria-label={`${starValue} star${starValue === 1 ? "" : "s"}`}
            disabled={disabled}
            tabIndex={isTabStop ? 0 : -1}
            onClick={onStarClick(starValue)}
            onKeyDown={(e) => handleKey(e, value ?? starValue)}
            onMouseEnter={() => !disabled && setHover(starValue)}
            className={cn(
              "rounded-lg p-1 transition-colors",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand",
              disabled && "opacity-50 cursor-not-allowed",
            )}
            data-testid={`star-selector-${starValue}`}
            data-filled={filled}
          >
            <svg
              viewBox="0 0 20 20"
              aria-hidden="true"
              className={cn(
                SIZE_CLASS[size],
                filled ? "text-brand" : "text-fg-subtle",
              )}
            >
              <path
                d={STAR_PATH}
                fill="currentColor"
                stroke="currentColor"
                strokeWidth="0.5"
              />
            </svg>
          </button>
        );
      })}
    </div>
  );
}
