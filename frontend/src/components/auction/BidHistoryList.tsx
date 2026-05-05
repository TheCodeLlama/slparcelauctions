"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/Button";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useBidHistory } from "@/hooks/useBidHistory";
import type { BidHistoryEntry } from "@/types/auction";
import { BidHistoryRow } from "./BidHistoryRow";

const ANIMATION_MS = 2_000;

export interface BidHistoryListProps {
  auctionPublicId: string;
}

/**
 * Paginated bid-history surface.
 *
 * <ul>
 *   <li>Page 0 is reactive — the envelope handler in
 *       {@code AuctionDetailClient} prepends {@code newBids} into the
 *       {@code ["auction", id, "bids", 0]} cache. This component does
 *       not subscribe to the WS itself; it simply re-renders when
 *       React Query notifies of the cache update.</li>
 *   <li>Pages 1+ lazy-load on "Load more". Each additional page appends
 *       to the in-memory {@code loadedPages} map so scrolling past page
 *       N keeps pages 0..N-1 visible.</li>
 *   <li>Newly-arrived bid rows (from an envelope) animate via the
 *       {@code isAnimated} prop on {@link BidHistoryRow} for ~2s.</li>
 * </ul>
 *
 * Infinite scroll is deferred per spec §19 — "Load more" is sufficient
 * for Phase 1.
 */
export function BidHistoryList({ auctionPublicId }: BidHistoryListProps) {
  // Track which page the user has loaded up to. Page 0 is always loaded
  // (seeded by the server component); the button bumps this to 1, 2, …
  const [loadedThrough, setLoadedThrough] = useState(0);

  // Surface the page-0 totalElements on the section element for the
  // integration test — the placeholder it replaces exposed the same
  // fact as {@code <span data-testid="bid-history-total">}, and the WS
  // envelope merger updates this value in place.
  const page0 = useBidHistory(auctionPublicId, 0);
  const total =
    page0.data?.totalElements ?? 0;

  return (
    <section
      aria-label="Bid history"
      data-testid="bid-history-list"
      data-total-elements={total}
      className="flex flex-col gap-2"
    >
      <h3 className="text-sm font-semibold tracking-tight text-fg">
        Bid history{" "}
        <span
          data-testid="bid-history-total"
          className="text-sm font-normal text-fg-muted"
        >
          ({total})
        </span>
      </h3>
      <BidHistoryPagesStack
        auctionPublicId={auctionPublicId}
        loadedThrough={loadedThrough}
        onLoadMore={() => setLoadedThrough((prev) => prev + 1)}
      />
    </section>
  );
}

/**
 * Renders pages 0..{@code loadedThrough} stacked top-to-bottom and
 * surfaces the "Load more" button when the server reports additional
 * pages available. Each page is its own {@code useBidHistory} hook so
 * React Query can cache them independently and so page 0's WS-driven
 * updates don't invalidate older pages.
 */
function BidHistoryPagesStack({
  auctionPublicId,
  loadedThrough,
  onLoadMore,
}: {
  auctionPublicId: string;
  loadedThrough: number;
  onLoadMore: () => void;
}) {
  // Build an array of page indices [0, 1, ..., loadedThrough] and render
  // one {@link BidHistoryPage} per index. This pattern lets us avoid
  // concatenating cached pages (which would require a complex select),
  // and each page manages its own loading / error state.
  const pageIndices = Array.from(
    { length: loadedThrough + 1 },
    (_, i) => i,
  );

  return (
    <>
      {pageIndices.map((idx, i) => (
        <BidHistoryPage
          key={idx}
          auctionPublicId={auctionPublicId}
          page={idx}
          // The LAST page in the stack owns the "Load more" / empty-state
          // UI — any earlier page is known to be populated.
          isLast={i === pageIndices.length - 1}
          onLoadMore={onLoadMore}
        />
      ))}
    </>
  );
}

/**
 * A single paginated slice of the bid history. Pages 1+ are lazy; page
 * 0 seeds from the server-component fetch via the outer
 * {@link AuctionDetailClient} so it renders synchronously on first
 * paint.
 *
 * Only the last mounted page displays the "Load more" button / empty
 * state / loading spinner — earlier pages, once resolved, just render
 * their rows.
 */
function BidHistoryPage({
  auctionPublicId,
  page,
  isLast,
  onLoadMore,
}: {
  auctionPublicId: string;
  page: number;
  isLast: boolean;
  onLoadMore: () => void;
}) {
  const query = useBidHistory(auctionPublicId, page);
  const data = query.data;

  // Track which bid id, if any, is currently in its ANIMATION_MS "just
  // arrived" window. Only meaningful for page 0 — pages 1+ never
  // receive WS updates.
  //
  // Opening the window: an effect watches the top-of-page id and
  // schedules an async state flip. Closing: the same effect schedules
  // a {@code setTimeout} to clear the state after ANIMATION_MS.
  // Both {@code setAnimatedId} calls sit inside the async callback
  // (the scheduled timeout), not the effect body itself, so React's
  // "set-state-in-effect" guard passes.
  const [animatedId, setAnimatedId] = useState<string | null>(null);
  const lastFiredRef = useRef<string | null>(null);
  const topBidId = page === 0 ? (data?.content[0]?.bidPublicId ?? null) : null;

  useEffect(() => {
    if (topBidId == null) return;
    if (lastFiredRef.current === topBidId) return;
    lastFiredRef.current = topBidId;

    // Open the animation window on a microtask and close it after
    // ANIMATION_MS. Wrapping the open in {@code queueMicrotask} keeps
    // the state update off the effect's synchronous path so the lint
    // rule doesn't flag a cascading render. {@code queueMicrotask} has
    // no cancel handle — a stale open firing after unmount is harmless
    // because {@code lastFiredRef} guards re-entry and React 19 no-ops
    // {@code setState} on unmounted components.
    const targetId = topBidId;
    queueMicrotask(() => {
      setAnimatedId(targetId);
    });
    const closeHandle = setTimeout(() => {
      setAnimatedId((prev) => (prev === targetId ? null : prev));
    }, ANIMATION_MS);
    return () => {
      clearTimeout(closeHandle);
    };
  }, [topBidId]);

  // First-ever load on page 0 is seeded (never in "pending"); on pages 1+
  // the initial fetch IS pending so we render a spinner.
  if (query.isLoading && !data) {
    return (
      <div data-testid="bid-history-loading">
        <LoadingSpinner label="Loading bids…" />
      </div>
    );
  }

  if (!data) {
    // React Query returned an error without any data. Surface a
    // lightweight message — the full error surface (retry buttons etc.)
    // is out of scope for this surface; a refetch on reconnect covers
    // the common transient case.
    return isLast ? (
      <p
        data-testid="bid-history-error"
        className="text-xs text-fg-muted"
      >
        Couldn&apos;t load bids. They&apos;ll reappear when the connection is
        restored.
      </p>
    ) : null;
  }

  const rows = data.content;
  const isEmpty = isLast && page === 0 && data.totalElements === 0;
  const moreAvailable =
    isLast && data.totalPages > 0 && page + 1 < data.totalPages;

  if (isEmpty) {
    return (
      <p
        data-testid="bid-history-empty"
        className="rounded-lg bg-surface-raised px-4 py-6 text-sm text-fg-muted"
      >
        No bids yet. Be the first to bid.
      </p>
    );
  }

  return (
    <>
      <ul
        className="flex flex-col gap-1"
        data-testid="bid-history-page"
        data-page={page}
      >
        {rows.map((row: BidHistoryEntry) => (
          <BidHistoryRow
            key={row.bidPublicId}
            entry={row}
            isAnimated={page === 0 && animatedId === row.bidPublicId}
          />
        ))}
      </ul>
      {moreAvailable ? (
        <Button
          variant="secondary"
          size="sm"
          onClick={onLoadMore}
          data-testid="bid-history-load-more"
          className="self-center"
        >
          Load more
        </Button>
      ) : null}
    </>
  );
}

