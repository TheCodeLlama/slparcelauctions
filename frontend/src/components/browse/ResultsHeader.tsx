"use client";
import { SortDropdown } from "./SortDropdown";
import { MenuIcon } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import type { AuctionSort } from "@/types/search";

export interface ResultsHeaderProps {
  title?: string;
  total: number;
  sort: AuctionSort;
  onSortChange: (next: AuctionSort) => void;
  /** Clicking the mobile trigger opens the bottom-sheet filter panel. */
  onOpenMobile?: () => void;
  nearestEnabled: boolean;
  className?: string;
}

/**
 * Header strip above the results grid. Title + count on the left, the
 * mobile filter trigger + sort dropdown on the right. The mobile trigger
 * is hidden on md+ screens because the sidebar is always visible there.
 */
export function ResultsHeader({
  title = "Browse",
  total,
  sort,
  onSortChange,
  onOpenMobile,
  nearestEnabled,
  className,
}: ResultsHeaderProps) {
  return (
    <header
      className={cn(
        "flex flex-wrap items-center justify-between gap-3",
        className,
      )}
    >
      <div>
        <h1 className="text-lg font-bold tracking-tight">
          {title}{" "}
          <span className="text-fg-subtle font-medium">
            · {total.toLocaleString()} result{total === 1 ? "" : "s"}
          </span>
        </h1>
      </div>
      <div className="flex items-center gap-2">
        {onOpenMobile && (
          <button
            type="button"
            onClick={onOpenMobile}
            aria-label="Filters"
            className="md:hidden inline-flex items-center gap-2 rounded-lg bg-bg-subtle px-3 py-2 text-xs font-medium text-fg"
          >
            <MenuIcon className="size-4" aria-hidden="true" />
            <span>Filters</span>
          </button>
        )}
        <SortDropdown
          value={sort}
          onChange={onSortChange}
          nearestEnabled={nearestEnabled}
        />
      </div>
    </header>
  );
}
