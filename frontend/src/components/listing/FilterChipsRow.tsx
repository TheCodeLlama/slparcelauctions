"use client";

import { cn } from "@/lib/cn";
import { MY_LISTINGS_FILTERS, type MyListingsFilter } from "@/hooks/useMyListings";

export interface FilterChipsRowProps {
  value: MyListingsFilter;
  onChange: (next: MyListingsFilter) => void;
  /**
   * When zero, the "Suspended" chip is hidden (spec §6.2). Kept separate
   * from the available-filter list so the caller can pass the count from
   * {@link useMyListingsSuspendedCount} without re-deriving it.
   */
  suspendedCount: number;
  /** Total count of all listings — drives the "All" chip badge. */
  totalCount: number;
  className?: string;
}

/**
 * Chip-style filter row for the My Listings dashboard tab. Renders one
 * button per bucket in {@link MY_LISTINGS_FILTERS}; the "Suspended" chip
 * is suppressed when {@code suspendedCount === 0} so sellers who have
 * never had a listing suspended never see the term in the UI.
 *
 * Accessibility: rendered as a {@code role="tablist"} with each chip a
 * {@code role="tab"} + {@code aria-selected} so screen readers announce
 * the selection like a segmented control. The visual chips are regular
 * buttons — no keyboard-arrow navigation (Headless UI Tabs would give
 * that, but for a flat filter the extra machinery isn't worth the
 * dependency on a controlling parent).
 */
export function FilterChipsRow({
  value,
  onChange,
  suspendedCount,
  totalCount,
  className,
}: FilterChipsRowProps) {
  return (
    <div
      role="tablist"
      aria-label="Filter listings"
      className={cn("flex flex-wrap gap-2", className)}
    >
      {MY_LISTINGS_FILTERS.map((f) => {
        if (f === "Suspended" && suspendedCount === 0) return null;
        const selected = f === value;
        const badge = badgeFor(f, { suspendedCount, totalCount });
        return (
          <button
            key={f}
            type="button"
            role="tab"
            aria-selected={selected}
            onClick={() => onChange(f)}
            className={cn(
              "inline-flex items-center gap-2 rounded-full px-3.5 py-1.5 text-xs font-medium transition-colors",
              selected
                ? "bg-brand text-on-primary"
                : "bg-bg-subtle text-fg hover:bg-bg-muted",
            )}
          >
            <span>{f}</span>
            {badge != null && (
              <span
                className={cn(
                  "inline-flex min-w-5 items-center justify-center rounded-full px-1.5 text-[11px] font-medium",
                  selected
                    ? "bg-on-primary/20 text-on-primary"
                    : "bg-bg-hover text-fg-muted",
                )}
              >
                {badge}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

function badgeFor(
  f: MyListingsFilter,
  counts: { suspendedCount: number; totalCount: number },
): number | null {
  if (f === "All") return counts.totalCount;
  if (f === "Suspended") return counts.suspendedCount;
  return null;
}
