"use client";
import { useId } from "react";
import { cn } from "@/lib/cn";
import type {
  AuctionSearchQuery,
  AuctionSort,
  SavedStatusFilter,
} from "@/types/search";

export interface CuratorTrayHeaderProps {
  count: number;
  query: AuctionSearchQuery;
  onQueryChange: (next: AuctionSearchQuery) => void;
  className?: string;
}

/**
 * Sort modes available on the Curator Tray. {@code nearest} is omitted
 * because the tray has no near-region input surface; callers who want a
 * location-weighted view can visit /browse.
 */
const SORT_OPTIONS: Array<{ value: AuctionSort; label: string }> = [
  { value: "newest", label: "Newest" },
  { value: "ending_soonest", label: "Ending soonest" },
  { value: "most_bids", label: "Most bids" },
  { value: "lowest_price", label: "Lowest price" },
  { value: "largest_area", label: "Largest area" },
];

const STATUS_OPTIONS: Array<{ value: SavedStatusFilter; label: string }> = [
  { value: "active_only", label: "Active only" },
  { value: "all", label: "All" },
  { value: "ended_only", label: "Ended only" },
];

/**
 * Sticky header for the Curator Tray shells (drawer + page). Shows the
 * total saved count and the two controls the user has over the list:
 * sort mode and status filter. Native selects mirror the SortDropdown
 * pattern used on /browse.
 */
export function CuratorTrayHeader({
  count,
  query,
  onQueryChange,
  className,
}: CuratorTrayHeaderProps) {
  const sortId = useId();
  const statusId = useId();
  const sort: AuctionSort = query.sort ?? "newest";
  const status: SavedStatusFilter = query.statusFilter ?? "active_only";

  return (
    <header
      className={cn(
        "flex flex-col gap-3",
        className,
      )}
    >
      <h2 className="text-sm font-semibold tracking-tight font-bold font-display tracking-[-0.02em]">
        Your Curator Tray ({count.toLocaleString()} saved)
      </h2>
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-2">
          <label
            htmlFor={sortId}
            className="text-xs font-medium text-fg-muted"
          >
            Sort
          </label>
          <select
            id={sortId}
            value={sort}
            onChange={(e) =>
              onQueryChange({
                ...query,
                sort: e.target.value as AuctionSort,
                page: 0,
              })
            }
            className="h-9 rounded-lg bg-bg-subtle text-fg px-3 text-xs font-medium focus:outline-none focus:ring-1 focus:ring-brand"
          >
            {SORT_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
        <div className="flex items-center gap-2">
          <label
            htmlFor={statusId}
            className="text-xs font-medium text-fg-muted"
          >
            Status
          </label>
          <select
            id={statusId}
            value={status}
            onChange={(e) =>
              onQueryChange({
                ...query,
                statusFilter: e.target.value as SavedStatusFilter,
                page: 0,
              })
            }
            className="h-9 rounded-lg bg-bg-subtle text-fg px-3 text-xs font-medium focus:outline-none focus:ring-1 focus:ring-brand"
          >
            {STATUS_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
      </div>
    </header>
  );
}
