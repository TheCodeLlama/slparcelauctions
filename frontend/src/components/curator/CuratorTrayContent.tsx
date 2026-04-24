"use client";
import { useState } from "react";
import { Pagination } from "@/components/ui/Pagination";
import { ResultsGrid } from "@/components/browse/ResultsGrid";
import { isApiError } from "@/lib/api";
import { useSavedAuctions } from "@/hooks/useSavedAuctions";
import { defaultAuctionSearchQuery } from "@/lib/search/url-codec";
import type { AuctionSearchQuery } from "@/types/search";
import { CuratorTrayHeader } from "./CuratorTrayHeader";
import { CuratorTrayEmpty } from "./CuratorTrayEmpty";

export interface CuratorTrayContentProps {
  /**
   * Current query driving the saved-auctions fetch. If {@link onQueryChange}
   * is provided the caller owns state (used by /saved, which mirrors the
   * URL); otherwise this component owns state internally (used by the
   * tray drawer / bottom sheet).
   */
  query?: AuctionSearchQuery;
  onQueryChange?: (next: AuctionSearchQuery) => void;
  className?: string;
}

const INITIAL_QUERY: AuctionSearchQuery = {
  ...defaultAuctionSearchQuery,
  statusFilter: "active_only",
};

/**
 * Shared body rendered by both the Curator Tray drawer/sheet and the
 * {@code /saved} page. State ownership is flipped by the presence of
 * {@code onQueryChange}: the drawer shell doesn't URL-sync (the tray is
 * ephemeral — closing loses the filter state), while the page shell
 * threads every change through {@code router.replace}.
 */
export function CuratorTrayContent({
  query: controlledQuery,
  onQueryChange,
  className,
}: CuratorTrayContentProps) {
  const isControlled = typeof onQueryChange === "function";
  const [internalQuery, setInternalQuery] = useState<AuctionSearchQuery>(
    controlledQuery ?? INITIAL_QUERY,
  );
  const query: AuctionSearchQuery =
    isControlled && controlledQuery ? controlledQuery : internalQuery;

  const applyQuery = (next: AuctionSearchQuery) => {
    if (isControlled && onQueryChange) {
      onQueryChange(next);
    } else {
      setInternalQuery(next);
    }
  };

  const result = useSavedAuctions(query);
  const errorCode =
    result.error && isApiError(result.error)
      ? (result.error.problem?.code as string | undefined)
      : undefined;

  const count = result.data?.totalElements ?? 0;
  const listings = result.data?.content ?? [];
  const showEmpty =
    !result.isLoading &&
    !result.isError &&
    listings.length === 0 &&
    !query.region &&
    !query.minPrice &&
    !query.maxPrice &&
    !query.tags?.length;

  return (
    <div className={className}>
      <CuratorTrayHeader
        count={count}
        query={query}
        onQueryChange={applyQuery}
        className="mb-4"
      />
      {showEmpty && query.statusFilter === "active_only" ? (
        <CuratorTrayEmpty />
      ) : (
        <ResultsGrid
          listings={listings}
          isLoading={result.isLoading}
          isError={result.isError}
          errorCode={errorCode}
          query={query}
          onClearFilters={() => applyQuery({ ...INITIAL_QUERY })}
          onRetry={() => result.refetch()}
          variant="compact"
        />
      )}
      {result.data && result.data.totalPages > 1 && (
        <Pagination
          page={result.data.page}
          totalPages={result.data.totalPages}
          onPageChange={(page) => applyQuery({ ...query, page })}
          className="mt-4"
        />
      )}
    </div>
  );
}
