"use client";

import Link from "next/link";
import { Avatar } from "@/components/ui/Avatar";
import { Card } from "@/components/ui/Card";
import { ArrowRight } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { usePendingReviews } from "@/hooks/useReviews";
import type { PendingReviewDto } from "@/types/review";

/**
 * "Closes in N days" when ≥ 24h left, otherwise "Closes in N hours". The
 * backend already sorts most-urgent-first so the UI does not round-trip
 * timestamps; {@code hoursRemaining} is the single source of truth.
 *
 * @param hoursRemaining whole hours until the review window closes
 */
export function formatWindowRemaining(hoursRemaining: number): string {
  const hours = Math.max(0, Math.floor(hoursRemaining));
  if (hours === 0) {
    return "Closes soon";
  }
  if (hours < 24) {
    return `Closes in ${hours} hour${hours === 1 ? "" : "s"}`;
  }
  const days = Math.floor(hours / 24);
  return `Closes in ${days} day${days === 1 ? "" : "s"}`;
}

/**
 * Role-label for the row header — maps backend enum to the spec §8.4 copy
 * ("Aurora Parcel · Seller"). The viewer's role is embedded in the DTO so
 * one "pending reviews" list cleanly covers both seller-side and
 * buyer-side rows.
 */
function roleLabel(role: PendingReviewDto["viewerRole"]): string {
  return role === "SELLER" ? "Seller" : "Buyer";
}

function PendingReviewRow({ item }: { item: PendingReviewDto }) {
  const href = `/auction/${item.auctionPublicId}/escrow#review-panel`;
  return (
    <li
      className="flex flex-col gap-3 rounded-lg bg-bg-subtle p-4 ring-1 ring-border-subtle sm:flex-row sm:items-center"
      data-testid="pending-review-row"
      data-auction-id={item.auctionPublicId}
    >
      <div
        className={cn(
          "relative h-20 w-full shrink-0 overflow-hidden rounded-lg bg-bg-muted sm:w-28",
        )}
      >
        {item.primaryPhotoUrl && (
          // eslint-disable-next-line @next/next/no-img-element -- Deferred: swap to next/image when backend returns image dimensions + a stable remotePatterns list for SL CDN hosts is agreed upon. Matches the ListingCard deferral.
          <img
            src={item.primaryPhotoUrl}
            alt=""
            className="h-full w-full object-cover"
            loading="lazy"
          />
        )}
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex items-baseline gap-2">
          <span className="truncate text-sm font-semibold text-fg">
            {item.title}
          </span>
          <span className="text-[11px] font-medium text-fg-muted">
            · {roleLabel(item.viewerRole)}
          </span>
        </div>
        <div className="flex items-center gap-2 text-sm text-fg-muted">
          <Avatar
            src={item.counterpartyAvatarUrl ?? undefined}
            alt={item.counterpartyDisplayName}
            name={item.counterpartyDisplayName}
            size="xs"
          />
          <span className="truncate">
            Leave a review for {item.counterpartyDisplayName}
          </span>
        </div>
        <p
          className="text-[11px] font-medium text-fg-muted"
          data-testid="pending-review-window"
        >
          {formatWindowRemaining(item.hoursRemaining)}
        </p>
      </div>
      <Link
        href={href}
        className="inline-flex items-center gap-1 self-start rounded-lg bg-brand px-3 py-2 text-xs font-medium text-white transition-opacity hover:opacity-90 focus-visible:ring-2 focus-visible:ring-brand sm:self-center"
        data-testid="pending-review-cta"
      >
        Leave a review
        <ArrowRight className="size-4" aria-hidden="true" />
      </Link>
    </li>
  );
}

/**
 * Dashboard "Pending reviews" card. Short-circuits to {@code null} when
 * there is nothing for the viewer to review so the dashboard is not
 * permanently burdened with an empty card. Each row deep-links to the
 * escrow page's {@code #review-panel} anchor — {@link ReviewPanel} carries
 * that id so the browser's native scroll handles the jump.
 */
export function PendingReviewsSection({ className }: { className?: string } = {}) {
  const { data, isPending, isError } = usePendingReviews();

  // Loading / error collapse to nothing — the section is additive and the
  // rest of the dashboard does not depend on it, so a failed fetch should
  // not poison the page. A toast is raised by the mutation hooks that
  // invalidate this query; no surface-level copy is required here.
  if (isPending || isError) return null;
  if (data.length === 0) return null;

  return (
    <Card className={className} data-testid="pending-reviews-section">
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight font-bold">Pending reviews</h2>
      </Card.Header>
      <Card.Body>
        <ul className="flex flex-col gap-3">
          {data.map((item) => (
            <PendingReviewRow key={item.auctionPublicId} item={item} />
          ))}
        </ul>
      </Card.Body>
    </Card>
  );
}
