"use client";
import { Search } from "@/components/ui/icons";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/EmptyState";

export type ResultsEmptyReason = "no-filters" | "no-match";

export interface ResultsEmptyProps {
  reason: ResultsEmptyReason;
  onClearFilters?: () => void;
}

/**
 * Empty-state copy for the browse grid. Two variants:
 *   - {@code no-filters}: the unfiltered page returned zero — there are
 *     no active auctions at all.
 *   - {@code no-match}: the user's filter combination is too narrow. A
 *     "Clear all filters" CTA lets them reset to the baseline.
 */
export function ResultsEmpty({ reason, onClearFilters }: ResultsEmptyProps) {
  if (reason === "no-filters") {
    return (
      <EmptyState
        icon={Search}
        headline="No active auctions yet"
        description="Check back soon — new parcels are listed throughout the day."
      />
    );
  }
  return (
    <EmptyState
      icon={Search}
      headline="No auctions match your filters"
      description="Try loosening your filters or clearing them to see every active listing."
    >
      {onClearFilters && (
        <Button variant="primary" onClick={onClearFilters}>
          Clear all filters
        </Button>
      )}
    </EmptyState>
  );
}
