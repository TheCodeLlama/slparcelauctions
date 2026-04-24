"use client";
import { useId } from "react";
import { cn } from "@/lib/cn";
import type { AuctionSort } from "@/types/search";

export interface SortDropdownProps {
  value: AuctionSort;
  onChange: (next: AuctionSort) => void;
  /**
   * Gate the "Nearest" option. The backend rejects
   * {@code sort=nearest} when {@code near_region} is absent (see
   * DISTANCE_REQUIRES_NEAR_REGION). The UI reflects the constraint by
   * disabling the option rather than letting the user pick an invalid combo.
   */
  nearestEnabled: boolean;
  className?: string;
}

const OPTIONS: Array<{ value: AuctionSort; label: string }> = [
  { value: "newest", label: "Newest" },
  { value: "ending_soonest", label: "Ending soonest" },
  { value: "most_bids", label: "Most bids" },
  { value: "lowest_price", label: "Lowest price" },
  { value: "largest_area", label: "Largest area" },
  { value: "nearest", label: "Nearest" },
];

/**
 * Semantic {@code <select>} for the six browse sort modes. A native
 * element is intentional — it keeps the screen-reader story trivial,
 * avoids a Headless UI Menu dependency here, and matches the mobile
 * platform's native picker.
 */
export function SortDropdown({
  value,
  onChange,
  nearestEnabled,
  className,
}: SortDropdownProps) {
  const id = useId();
  return (
    <div className={cn("flex items-center gap-2", className)}>
      <label
        htmlFor={id}
        className="text-label-md text-on-surface-variant"
      >
        Sort
      </label>
      <select
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value as AuctionSort)}
        className="h-9 rounded-default bg-surface-container-low text-on-surface px-3 text-label-md focus:outline-none focus:ring-1 focus:ring-primary"
      >
        {OPTIONS.map((o) => (
          <option
            key={o.value}
            value={o.value}
            disabled={o.value === "nearest" && !nearestEnabled}
          >
            {o.label}
          </option>
        ))}
      </select>
    </div>
  );
}
