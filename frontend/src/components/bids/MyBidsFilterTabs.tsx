"use client";

import { cn } from "@/lib/cn";
import type { MyBidsFilter } from "@/lib/api/myBids";

export const MY_BIDS_FILTERS: ReadonlyArray<{
  id: MyBidsFilter;
  label: string;
}> = [
  { id: "all", label: "All" },
  { id: "active", label: "Active" },
  { id: "won", label: "Won" },
  { id: "lost", label: "Lost" },
];

export interface MyBidsFilterTabsProps {
  /** Currently-selected filter; drives the {@code aria-checked} state. */
  value: MyBidsFilter;
  /** Fired on option click. Callers sync this to the URL {@code ?status=} param. */
  onChange: (next: MyBidsFilter) => void;
  className?: string;
}

/**
 * Four-way filter control for the My Bids dashboard (spec §12). Presented as
 * a {@code role="radiogroup"} rather than a {@code role="tablist"} because
 * the four options filter the same list of bid rows — there is no separate
 * tabpanel per option, which is what the ARIA tab pattern requires. A radio
 * group accurately models "pick one of N to filter" and needs no matching
 * panel element, so assistive tech announces the control correctly without
 * the incomplete-tab-pattern smell the original markup had.
 *
 * <p>The component is purely presentational — it does not touch the router;
 * the caller ({@code MyBidsTab}) owns the URL sync so this stays reusable.
 */
export function MyBidsFilterTabs({
  value,
  onChange,
  className,
}: MyBidsFilterTabsProps) {
  return (
    <div
      role="radiogroup"
      aria-label="Filter bids"
      className={cn(
        "flex gap-1 border-b border-outline-variant",
        className,
      )}
    >
      {MY_BIDS_FILTERS.map((tab) => {
        const selected = tab.id === value;
        return (
          <button
            key={tab.id}
            type="button"
            role="radio"
            aria-checked={selected}
            onClick={() => onChange(tab.id)}
            className={cn(
              "px-4 py-2 text-label-lg transition-colors",
              selected
                ? "text-primary border-b-2 border-primary"
                : "text-on-surface-variant hover:text-on-surface",
            )}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
}
