"use client";
import { cn } from "@/lib/cn";

export interface RangeSliderProps {
  min: number;
  max: number;
  step?: number;
  value: [number, number];
  onChange: (value: [number, number]) => void;
  ariaLabel?: [string, string];
  className?: string;
}

/**
 * Dual-handle range input. Emits a new [low, high] tuple whenever either
 * handle changes. Handles are clamped so the low handle cannot cross above
 * the high handle and vice versa.
 *
 * Implementation is intentionally plain — two overlapping native
 * {@code <input type="range">} elements sharing a transparent track. The
 * visual polish of a shared filled track is a later iteration concern per
 * the plan (Step 2a.13 note).
 */
export function RangeSlider({
  min,
  max,
  step = 1,
  value,
  onChange,
  ariaLabel,
  className,
}: RangeSliderProps) {
  const [lo, hi] = value;
  return (
    <div className={cn("relative h-8", className)}>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={lo}
        aria-label={ariaLabel?.[0] ?? "Minimum"}
        aria-valuenow={lo}
        onChange={(e) => {
          const v = Number(e.target.value);
          onChange([Math.min(v, hi), hi]);
        }}
        className="absolute inset-x-0 top-0 w-full appearance-none bg-transparent accent-primary"
      />
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={hi}
        aria-label={ariaLabel?.[1] ?? "Maximum"}
        aria-valuenow={hi}
        onChange={(e) => {
          const v = Number(e.target.value);
          onChange([lo, Math.max(v, lo)]);
        }}
        className="absolute inset-x-0 top-0 w-full appearance-none bg-transparent accent-primary"
      />
    </div>
  );
}
