"use client";

import { useState } from "react";
import { Card } from "@/components/ui/Card";
import { ChevronDown, ChevronUp } from "@/components/ui/icons";
import { Pagination } from "@/components/ui/Pagination";
import { cn } from "@/lib/cn";
import { useCancellationHistory } from "@/hooks/useCancellationHistory";
import type { CancellationHistoryDto } from "@/types/cancellation";
import { CancellationConsequenceBadge } from "@/components/cancellation/CancellationConsequenceBadge";

/**
 * Renders {@code cancelledAt} as a stable date label. Hour-precision is
 * dropped on purpose — the row already conveys "this happened at some
 * point" and a precise timestamp adds noise without value.
 */
function formatCancelledAt(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

function CancellationHistoryRow({ row }: { row: CancellationHistoryDto }) {
  const [expanded, setExpanded] = useState(false);
  const hasReason = row.reason != null && row.reason.trim().length > 0;
  const reasonId = `cancellation-reason-${row.auctionId}`;

  return (
    <li
      className="flex flex-col gap-3 rounded-default bg-surface-container-low p-4 ring-1 ring-outline-variant"
      data-testid="cancellation-history-row"
      data-auction-id={row.auctionId}
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative h-20 w-full shrink-0 overflow-hidden rounded-default bg-surface-container sm:w-28">
          {row.primaryPhotoUrl && (
            // eslint-disable-next-line @next/next/no-img-element -- Deferred: swap to next/image when backend returns image dimensions + a stable remotePatterns list for SL CDN hosts is agreed upon. Matches the PendingReviewsSection deferral.
            <img
              src={row.primaryPhotoUrl}
              alt=""
              className="h-full w-full object-cover"
              loading="lazy"
            />
          )}
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="truncate text-title-md font-bold text-on-surface">
            {row.auctionTitle}
          </span>
          <p className="text-label-sm text-on-surface-variant">
            Cancelled {formatCancelledAt(row.cancelledAt)} ·{" "}
            {row.cancelledFromStatus}
            {row.hadBids ? " · had bids" : ""}
          </p>
        </div>
        <div className="shrink-0">
          <CancellationConsequenceBadge
            kind={row.penaltyApplied?.kind ?? null}
            amountL={row.penaltyApplied?.amountL ?? null}
          />
        </div>
      </div>
      {hasReason && (
        <div className="flex flex-col gap-1">
          <button
            type="button"
            onClick={() => setExpanded((prev) => !prev)}
            aria-expanded={expanded}
            aria-controls={reasonId}
            data-testid="cancellation-reason-toggle"
            className="inline-flex items-center gap-1 self-start rounded-default text-label-sm text-on-surface-variant hover:text-on-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
          >
            {expanded ? (
              <ChevronUp className="size-4" aria-hidden="true" />
            ) : (
              <ChevronDown className="size-4" aria-hidden="true" />
            )}
            {expanded ? "Hide reason" : "Show reason"}
          </button>
          {expanded && (
            <p
              id={reasonId}
              className={cn(
                "rounded-default bg-surface-container px-3 py-2 text-body-sm text-on-surface",
              )}
              data-testid="cancellation-reason-text"
            >
              {row.reason}
            </p>
          )}
        </div>
      )}
    </li>
  );
}

/**
 * Dashboard "Cancellation history" card (Epic 08 sub-spec 2 §8.3). Renders
 * a paginated, newest-first list of the seller's prior cancellations,
 * each annotated with a {@link CancellationConsequenceBadge} mapped from
 * the snapshotted log row's penalty payload. The section short-circuits
 * to {@code null} when the page is loading or errored — matches the
 * established {@code PendingReviewsSection} pattern from Sub-spec 1, so a
 * failed fetch never poisons the dashboard.
 *
 * <p>An explicit empty-state card renders "No cancellations yet." when the
 * seller has a clean record and the response is just an empty page. The
 * empty card still renders so the section is discoverable from the
 * dashboard — sellers can see "this is where my cancellations would
 * appear" before they hit the ladder, which is informational, not
 * accusatory.
 */
export function CancellationHistorySection({
  className,
}: { className?: string } = {}) {
  const [page, setPage] = useState(0);
  const { data, isPending, isError } = useCancellationHistory(page);

  if (isPending || isError) return null;

  if (data.totalElements === 0) {
    return (
      <Card
        className={className}
        data-testid="cancellation-history-section"
        data-variant="empty"
      >
        <Card.Header>
          <h2 className="text-title-md font-bold">Cancellation history</h2>
        </Card.Header>
        <Card.Body>
          <p className="text-body-md text-on-surface-variant">
            No cancellations yet.
          </p>
        </Card.Body>
      </Card>
    );
  }

  return (
    <Card className={className} data-testid="cancellation-history-section">
      <Card.Header>
        <h2 className="text-title-md font-bold">Cancellation history</h2>
      </Card.Header>
      <Card.Body>
        <ul className="flex flex-col gap-3">
          {data.content.map((row) => (
            <CancellationHistoryRow key={row.auctionId} row={row} />
          ))}
        </ul>
      </Card.Body>
      {data.totalPages > 1 && (
        <Card.Footer>
          <Pagination
            page={page}
            totalPages={data.totalPages}
            onPageChange={setPage}
          />
        </Card.Footer>
      )}
    </Card>
  );
}
