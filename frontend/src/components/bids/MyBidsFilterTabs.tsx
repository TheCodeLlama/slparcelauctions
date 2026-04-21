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
  /** Currently-selected filter; drives the {@code aria-selected} state. */
  value: MyBidsFilter;
  /** Fired on tab click. Callers sync this to the URL {@code ?status=} param. */
  onChange: (next: MyBidsFilter) => void;
  className?: string;
}

/**
 * Four-tab filter control for the My Bids dashboard (spec §12). Presented as
 * a {@code role="tablist"} so assistive tech announces the segmented control.
 * The component is purely presentational — it does not touch the router; the
 * caller ({@code MyBidsTab}) owns the URL sync so the tabs stay reusable.
 */
export function MyBidsFilterTabs({
  value,
  onChange,
  className,
}: MyBidsFilterTabsProps) {
  return (
    <div
      role="tablist"
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
            role="tab"
            aria-selected={selected}
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
