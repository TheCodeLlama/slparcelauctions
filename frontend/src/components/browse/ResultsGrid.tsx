"use client";
import { ListingCard } from "@/components/auction/ListingCard";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/EmptyState";
import { AlertCircle } from "@/components/ui/icons";
import { defaultAuctionSearchQuery } from "@/lib/search/url-codec";
import { canonicalKey } from "@/lib/search/canonical-key";
import { cn } from "@/lib/cn";
import type { AuctionSearchQuery, AuctionSearchResultDto } from "@/types/search";
import { ResultsEmpty } from "./ResultsEmpty";

export interface ResultsGridProps {
  listings: AuctionSearchResultDto[];
  isLoading: boolean;
  isError: boolean;
  errorCode?: string;
  query: AuctionSearchQuery;
  onClearFilters: () => void;
  onRetry?: () => void;
  /** Optional filters pinned by the surrounding page (e.g. {@code sellerId}). */
  fixedFilters?: Partial<AuctionSearchQuery>;
  className?: string;
}

const GRID_CLASSES = "grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3";
const SKELETON_COUNT = 8;

function ListingCardSkeleton() {
  return (
    <div
      aria-hidden="true"
      className="flex animate-pulse flex-col overflow-hidden rounded-default bg-surface-container-lowest shadow-sm"
    >
      <div className="aspect-[4/3] w-full bg-surface-container-high" />
      <div className="flex flex-col gap-2 p-4">
        <div className="h-5 w-3/4 rounded bg-surface-container-high" />
        <div className="h-4 w-1/2 rounded bg-surface-container-high" />
        <div className="h-6 w-1/3 rounded bg-surface-container-high" />
      </div>
    </div>
  );
}

/**
 * Compare two queries (excluding fixed filters, which are always present
 * on the surrounding page and shouldn't count as "applied filters").
 */
function hasAppliedFilters(
  query: AuctionSearchQuery,
  fixedFilters?: Partial<AuctionSearchQuery>,
): boolean {
  const baseline: AuctionSearchQuery = {
    ...defaultAuctionSearchQuery,
    ...(fixedFilters ?? {}),
  };
  return canonicalKey(query) !== canonicalKey(baseline);
}

/**
 * Results-surface state machine:
 *
 *   loading                   -> skeleton grid (8 placeholder cards)
 *   error (429)               -> "slow down" panel + retry button
 *   error (other)             -> generic error panel + retry button
 *   empty, no filters applied -> "no active auctions yet"
 *   empty, filters applied    -> "no match" + clear-all CTA
 *   populated                 -> responsive 3-column card grid
 */
export function ResultsGrid({
  listings,
  isLoading,
  isError,
  errorCode,
  query,
  onClearFilters,
  onRetry,
  fixedFilters,
  className,
}: ResultsGridProps) {
  if (isLoading) {
    return (
      <div
        role="status"
        aria-busy="true"
        aria-label="Loading listings"
        className={cn(GRID_CLASSES, className)}
      >
        {Array.from({ length: SKELETON_COUNT }, (_, i) => (
          <ListingCardSkeleton key={i} />
        ))}
      </div>
    );
  }

  if (isError) {
    const tooMany = errorCode === "TOO_MANY_REQUESTS";
    return (
      <EmptyState
        icon={AlertCircle}
        headline={
          tooMany
            ? "Too many searches — try again in a minute"
            : "Couldn't load listings"
        }
        description={
          tooMany
            ? "You've exceeded the search rate limit."
            : "Something went wrong while loading results."
        }
        className={className}
      >
        {onRetry && (
          <Button variant="primary" onClick={onRetry}>
            Try again
          </Button>
        )}
      </EmptyState>
    );
  }

  if (listings.length === 0) {
    const reason = hasAppliedFilters(query, fixedFilters) ? "no-match" : "no-filters";
    return (
      <div className={className}>
        <ResultsEmpty reason={reason} onClearFilters={onClearFilters} />
      </div>
    );
  }

  return (
    <div className={cn(GRID_CLASSES, className)}>
      {listings.map((listing) => (
        <ListingCard key={listing.id} listing={listing} variant="default" />
      ))}
    </div>
  );
}
