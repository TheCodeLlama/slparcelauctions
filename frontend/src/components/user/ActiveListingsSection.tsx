"use client";

import Link from "next/link";
import { AlertCircle, Building2, Gavel } from "@/components/ui/icons";
import { CountdownTimer } from "@/components/ui/CountdownTimer";
import { EmptyState } from "@/components/ui/EmptyState";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { cn } from "@/lib/cn";
import { apiUrl } from "@/lib/api/url";
import type { PublicAuctionResponse } from "@/types/auction";
import { useActiveListings } from "@/hooks/useActiveListings";

const PAGE_SIZE = 6;

export interface ActiveListingsSectionProps {
  userId: number;
  className?: string;
}

/**
 * Public-profile active-listings block (spec §14). Renders up to 6 auction
 * cards in a responsive grid:
 *   - 1 column on mobile
 *   - 2 columns at md breakpoint
 *   - 3 columns at lg breakpoint
 *
 * Data flows through {@link useActiveListings} ({@code GET
 * /api/v1/users/{id}/auctions?status=ACTIVE}) — public, no auth. A
 * "View all" link appears at the bottom when the seller has more than 6
 * active listings; the link target ({@code /users/{id}/listings}) is
 * implemented in Epic 07 Browse (see {@code docs/implementation/
 * DEFERRED_WORK.md}).
 */
export function ActiveListingsSection({
  userId,
  className,
}: ActiveListingsSectionProps) {
  const { data, isPending, isError } = useActiveListings(userId, {
    size: PAGE_SIZE,
  });

  if (isPending) {
    return (
      <div className={cn("flex justify-center py-6", className)}>
        <LoadingSpinner label="Loading listings..." />
      </div>
    );
  }

  if (isError) {
    return (
      <EmptyState
        icon={AlertCircle}
        headline="Could not load listings"
        className={className}
      />
    );
  }

  const listings = data.content;

  if (listings.length === 0) {
    return (
      <EmptyState
        icon={Gavel}
        headline="No active listings"
        className={className}
      />
    );
  }

  return (
    <div className={cn("flex flex-col gap-4", className)}>
      <ul
        className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3"
        aria-label="Active listings"
      >
        {listings.map((auction) => (
          <ActiveListingCard key={auction.id} auction={auction} />
        ))}
      </ul>
      {data.totalElements > PAGE_SIZE ? (
        <div className="flex justify-end">
          <Link
            href={`/users/${userId}/listings`}
            className="text-sm font-medium text-brand underline-offset-4 hover:underline"
          >
            View all ({data.totalElements})
          </Link>
        </div>
      ) : null}
    </div>
  );
}

function ActiveListingCard({ auction }: { auction: PublicAuctionResponse }) {
  const parcelLabel =
    auction.parcel.description?.trim() || "(unnamed parcel)";
  const thumb = apiUrl(auction.photos[0]?.url ?? auction.parcel.snapshotUrl);
  const highBid = numericHighBid(auction.currentHighBid);
  const endsAtDate = parseDate(auction.endsAt);

  return (
    <li className="rounded-lg border border-border-subtle bg-surface-raised">
      <Link
        href={`/auction/${auction.id}`}
        className="flex flex-col gap-2 p-3 hover:bg-bg-subtle focus-visible:bg-bg-subtle focus-visible:outline-none"
      >
        <Thumbnail src={thumb} alt="" />
        <div className="flex flex-col gap-1">
          <h3 className="text-sm font-semibold text-fg truncate">
            {parcelLabel}
          </h3>
          <p className="text-xs text-fg-muted truncate">
            {auction.parcel.regionName} · {auction.parcel.areaSqm.toLocaleString()} m²
          </p>
          <div className="flex items-baseline justify-between gap-2">
            <span className="text-xs text-fg-muted">
              Current{" "}
              <span className="font-semibold text-fg">
                {highBid == null
                  ? `L$${auction.startingBid.toLocaleString()}`
                  : `L$${highBid.toLocaleString()}`}
              </span>
            </span>
            {endsAtDate != null ? (
              <CountdownTimer
                expiresAt={endsAtDate}
                format="hh:mm:ss"
                className="inline text-xs"
              />
            ) : null}
          </div>
          <span className="text-xs font-medium text-brand underline-offset-4 hover:underline">
            View listing
          </span>
        </div>
      </Link>
    </li>
  );
}

function Thumbnail({ src, alt }: { src: string | null; alt: string }) {
  const box = "aspect-video w-full rounded-lg";
  if (src) {
    return (
      /* eslint-disable-next-line @next/next/no-img-element -- snapshot / MinIO-served bytes */
      <img src={src} alt={alt} className={cn(box, "object-cover")} />
    );
  }
  return (
    <div
      className={cn(
        box,
        "flex items-center justify-center bg-bg-hover text-fg-muted",
      )}
      aria-hidden="true"
    >
      <Building2 className="size-10" />
    </div>
  );
}

function numericHighBid(
  value: number | string | null | undefined,
): number | null {
  if (value == null) return null;
  const n = typeof value === "string" ? Number(value) : value;
  return Number.isFinite(n) && n > 0 ? n : null;
}

function parseDate(s: string | null | undefined): Date | null {
  if (!s) return null;
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? null : d;
}
