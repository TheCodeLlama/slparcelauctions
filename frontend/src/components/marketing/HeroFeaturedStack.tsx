"use client";

import Link from "next/link";
import type { AuctionSearchResultDto } from "@/types/search";
import { cn } from "@/lib/cn";

/**
 * Hero right-side 3D card stack: up to three featured auctions rendered as
 * layered, rotated cards. Empty state hidden gracefully (caller may pass an
 * empty array if the featured fetch failed).
 */
export function HeroFeaturedStack({ featured }: { featured: AuctionSearchResultDto[] }) {
  const cards = featured.slice(0, 3);
  if (cards.length === 0) {
    return <div className="hidden lg:block" aria-hidden />;
  }
  return (
    <div className="relative hidden h-[420px] lg:block">
      {cards.map((listing, i) => (
        <Link
          key={listing.publicId}
          href={`/auction/${listing.publicId}`}
          className={cn(
            "absolute block overflow-hidden rounded-lg border border-border bg-surface-raised shadow-md transition-transform",
            "hover:shadow-lg",
            i === 0 && "left-0 right-[60px] top-0 -rotate-1",
            i === 1 && "left-[24px] right-[30px] top-[14px] rotate-0",
            i === 2 && "left-[48px] right-0 top-[28px] rotate-1",
          )}
          style={{ zIndex: 3 - i }}
        >
          <div className="relative aspect-[16/10] bg-bg-muted">
            {(listing.primaryPhotoUrl ?? listing.parcel.snapshotUrl) && (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={listing.primaryPhotoUrl ?? listing.parcel.snapshotUrl ?? ""}
                alt=""
                className="h-full w-full object-cover"
                loading="eager"
              />
            )}
            <div className="absolute bottom-2 left-2 rounded-xs bg-fg/45 px-1.5 py-0.5 font-mono text-[10.5px] text-bg backdrop-blur-sm">
              {listing.parcel.region}
            </div>
          </div>
          <div className="flex items-center justify-between gap-3 px-3.5 py-3">
            <div className="min-w-0">
              <div className="truncate text-sm font-semibold text-fg">{listing.title}</div>
              <div className="font-mono text-[10.5px] text-fg-subtle">{listing.parcel.name}</div>
            </div>
            <div className="text-right">
              <div className="text-[11px] text-fg-subtle">Bid</div>
              <div className="text-sm font-semibold tabular-nums text-fg">L$ {listing.currentBid.toLocaleString()}</div>
            </div>
          </div>
        </Link>
      ))}
    </div>
  );
}
