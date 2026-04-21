"use client";

import { useCallback, useMemo } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/EmptyState";
import { FormError } from "@/components/ui/FormError";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Gavel } from "@/components/ui/icons";
import { useMyBids } from "@/hooks/useMyBids";
import { ApiError, isApiError } from "@/lib/api";
import type { MyBidsFilter } from "@/lib/api/myBids";
import type { MyBidSummary } from "@/types/auction";
import { MyBidsFilterTabs } from "./MyBidsFilterTabs";
import { MyBidSummaryRow } from "./MyBidSummaryRow";

const VALID_FILTERS: ReadonlySet<MyBidsFilter> = new Set([
  "all",
  "active",
  "won",
  "lost",
]);

function parseFilter(raw: string | null): MyBidsFilter {
  if (raw && VALID_FILTERS.has(raw as MyBidsFilter)) {
    return raw as MyBidsFilter;
  }
  return "all";
}

/**
 * Per-filter empty-state copy (spec §12). "All" distinguishes the genuine
 * zero-bids-ever case from the filtered subsets so new bidders get a clear
 * "you haven't bid yet" instead of a misleading "no X here".
 */
const EMPTY_COPY: Record<MyBidsFilter, { headline: string; description: string }> = {
  all: {
    headline: "No bids yet",
    description:
      "When you place a bid on an auction, it will show up here.",
  },
  active: {
    headline: "No active bids",
    description: "Bids on live auctions will appear here.",
  },
  won: {
    headline: "No won auctions yet",
    description: "Auctions you win will show up here once they end.",
  },
  lost: {
    headline: "Nothing in the Lost column",
    description: "Auctions you didn't win will appear here.",
  },
};

/**
 * Top-level content for {@code /dashboard/bids}. Composes:
 *   - {@link MyBidsFilterTabs} (All / Active / Won / Lost) — URL-synced
 *   - React Query-driven list of {@link MyBidSummaryRow}s
 *   - "Load more" button — paginates through
 *     {@link useMyBids}'s infinite query until {@code hasNextPage} goes false
 *
 * Empty-state handling per spec §12: the "all" filter speaks to the genuine
 * zero-bids-ever case; the other three use filter-scoped copy so a new user
 * on /dashboard/bids?status=won sees the bucket-specific prompt rather than
 * the new-bidder introduction.
 *
 * <p>Pagination uses React Query's {@code useInfiniteQuery} so the page
 * accumulator lives in the cache rather than component state — switching
 * filters swaps keys cleanly and the "Load more" button only needs to call
 * {@code fetchNextPage}.
 */
export function MyBidsTab() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const filter = parseFilter(searchParams.get("status"));

  const {
    data,
    isLoading,
    isError,
    error,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
  } = useMyBids(filter);

  const rows = useMemo<MyBidSummary[]>(() => {
    if (!data) return [];
    // Flatten pages and dedupe by auction.id so a page-0 refetch that
    // re-emits rows already loaded on page 1 doesn't duplicate them.
    const seen = new Set<number>();
    const out: MyBidSummary[] = [];
    for (const p of data.pages) {
      for (const row of p.content) {
        if (!seen.has(row.auction.id)) {
          seen.add(row.auction.id);
          out.push(row);
        }
      }
    }
    return out;
  }, [data]);

  const handleFilterChange = useCallback(
    (next: MyBidsFilter) => {
      const params = new URLSearchParams(searchParams.toString());
      if (next === "all") {
        params.delete("status");
      } else {
        params.set("status", next);
      }
      const qs = params.toString();
      router.replace(qs ? `${pathname}?${qs}` : pathname);
    },
    [pathname, router, searchParams],
  );

  const handleLoadMore = useCallback(() => {
    fetchNextPage();
  }, [fetchNextPage]);

  if (isLoading) {
    return (
      <div className="flex flex-col gap-4">
        <MyBidsFilterTabs value={filter} onChange={handleFilterChange} />
        <div className="flex justify-center py-12">
          <LoadingSpinner />
        </div>
      </div>
    );
  }

  if (isError) {
    const message =
      error instanceof ApiError || isApiError(error)
        ? (error.problem.detail ??
          error.problem.title ??
          "Couldn't load your bids.")
        : error instanceof Error
          ? error.message
          : "Couldn't load your bids.";
    return (
      <div className="flex flex-col gap-4">
        <MyBidsFilterTabs value={filter} onChange={handleFilterChange} />
        <FormError message={message} />
      </div>
    );
  }

  const copy = EMPTY_COPY[filter];

  return (
    <div className="flex flex-col gap-4">
      <MyBidsFilterTabs value={filter} onChange={handleFilterChange} />
      {rows.length === 0 ? (
        <EmptyState
          icon={Gavel}
          headline={copy.headline}
          description={copy.description}
        />
      ) : (
        <>
          <ul className="flex flex-col gap-2" aria-label="My bids">
            {rows.map((bid) => (
              <MyBidSummaryRow key={bid.auction.id} bid={bid} />
            ))}
          </ul>
          {hasNextPage ? (
            <div className="flex justify-center pt-2">
              <Button
                variant="secondary"
                onClick={handleLoadMore}
                disabled={isFetchingNextPage}
              >
                {isFetchingNextPage ? "Loading…" : "Load more"}
              </Button>
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}
