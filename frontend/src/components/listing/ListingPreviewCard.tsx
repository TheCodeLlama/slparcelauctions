import { MapPin, Tag as TagIcon } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import type { SellerAuctionResponse } from "@/types/auction";

/**
 * Read-only preview of a listing. Used on:
 *   - the Create-listing Review step (isPreview=true)
 *   - the seller preview page (GET /auctions/{id}/preview)
 *   - the Epic 04 public listing page will later import this too.
 *
 * Takes a narrow subset of SellerAuctionResponse so it also works with a
 * draft-shaped object that has photos as URLs and tags as ParcelTagDto[].
 */
export type ListingPreviewAuction = Pick<
  SellerAuctionResponse,
  | "parcel"
  | "startingBid"
  | "reservePrice"
  | "buyNowPrice"
  | "durationHours"
  | "tags"
  | "photos"
  | "sellerDesc"
>;

export interface ListingPreviewCardProps {
  auction: ListingPreviewAuction;
  /** When true, renders the "Preview — this is how buyers will see it" banner. */
  isPreview?: boolean;
  className?: string;
}

export function ListingPreviewCard({
  auction,
  isPreview = false,
  className,
}: ListingPreviewCardProps) {
  const cover = auction.photos[0]?.url ?? null;
  const title = auction.parcel.description?.trim() || "(unnamed parcel)";
  return (
    <article
      className={cn(
        "flex flex-col gap-3 rounded-default border border-outline-variant bg-surface-container-lowest p-4",
        className,
      )}
    >
      {isPreview && (
        <div className="rounded-default bg-primary-container px-3 py-2 text-body-sm text-on-primary-container">
          Preview — this is how your listing will appear to buyers.
        </div>
      )}
      {cover ? (
        /* eslint-disable-next-line @next/next/no-img-element */
        <img
          src={cover}
          alt=""
          className="h-48 w-full rounded-default object-cover"
        />
      ) : null}
      <h2 className="text-title-lg text-on-surface">{title}</h2>
      <p className="flex items-center gap-1 text-body-sm text-on-surface-variant">
        <MapPin className="size-3.5" aria-hidden="true" />
        <span>
          {auction.parcel.regionName} · {auction.parcel.areaSqm} m²
        </span>
      </p>
      <dl className="grid grid-cols-3 gap-4">
        <Stat label="Starting bid" value={`L$${auction.startingBid}`} />
        {auction.reservePrice != null && auction.reservePrice > 0 && (
          <Stat label="Reserve" value="set" />
        )}
        {auction.buyNowPrice != null && auction.buyNowPrice > 0 && (
          <Stat label="Buy it now" value={`L$${auction.buyNowPrice}`} />
        )}
        <Stat
          label="Duration"
          value={formatDuration(auction.durationHours)}
        />
      </dl>
      {auction.sellerDesc && (
        <p className="whitespace-pre-wrap text-body-md text-on-surface">
          {auction.sellerDesc}
        </p>
      )}
      {auction.tags.length > 0 && (
        <ul className="flex flex-wrap gap-1">
          {auction.tags.map((t) => (
            <li
              key={t.code}
              className="inline-flex items-center gap-1 rounded-full bg-surface-container-high px-2 py-0.5 text-label-sm text-on-surface-variant"
            >
              <TagIcon className="size-3" aria-hidden="true" />
              {t.label}
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-label-sm uppercase text-on-surface-variant">
        {label}
      </dt>
      <dd className="text-body-md font-medium text-on-surface">{value}</dd>
    </div>
  );
}

function formatDuration(hours: number): string {
  if (hours % 24 === 0) {
    const days = hours / 24;
    return days === 1 ? "1 day" : `${days} days`;
  }
  return `${hours} hours`;
}
