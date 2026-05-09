"use client";

import { MapPin } from "@/components/ui/icons";
import { apiUrl } from "@/lib/api/url";
import type { SuggestListing, SuggestRegion } from "@/lib/api/search-suggest";

function ListingRow({ listing }: { listing: SuggestListing }) {
  const src = apiUrl(listing.primaryPhotoUrl) ?? null;
  return (
    <div className="flex items-center gap-3 px-3 py-2">
      <div className="relative h-10 w-10 shrink-0 overflow-hidden rounded-md bg-bg-muted">
        {src ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={src} alt="" className="h-full w-full object-cover" />
        ) : (
          <MapPin className="m-2 size-6 text-fg-muted" />
        )}
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-semibold text-fg">
          {listing.title}
        </div>
        <div className="truncate font-mono text-[10.5px] text-fg-subtle">
          {listing.regionName} · L$ {listing.currentBid.toLocaleString()}
        </div>
      </div>
    </div>
  );
}

function RegionRow({ region }: { region: SuggestRegion }) {
  return (
    <div className="flex items-center gap-3 px-3 py-2">
      <div className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-bg-muted">
        <MapPin className="size-5 text-fg-muted" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-semibold text-fg">{region.name}</div>
        <div className="font-mono text-[10.5px] text-fg-subtle">
          {region.activeAuctionCount} active
        </div>
      </div>
    </div>
  );
}

export const SearchResultRow = {
  Listing: ListingRow,
  Region: RegionRow,
};
