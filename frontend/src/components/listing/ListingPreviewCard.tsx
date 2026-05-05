import { MapPin, Tag as TagIcon } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { apiUrl } from "@/lib/api/url";
import { resolveListingHeadline } from "@/lib/listing/resolveListingHeadline";
import type { SellerAuctionResponse } from "@/types/auction";

/**
 * Read-only preview of a listing. Used on:
 *   - the activate page DRAFT state (isPreview=true)
 *   - the seller preview page (GET /auctions/{id}/preview)
 *
 * Takes a narrow subset of SellerAuctionResponse so it also works with a
 * draft-shaped object that has photos as URLs and tags as ParcelTagDto[].
 */
export type ListingPreviewAuction = Pick<
  SellerAuctionResponse,
  | "title"
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
  /** When true, renders the "Preview: this is how buyers will see it" banner. */
  isPreview?: boolean;
  className?: string;
}

export function ListingPreviewCard({
  auction,
  isPreview = false,
  className,
}: ListingPreviewCardProps) {
  const cover = apiUrl(auction.photos[0]?.url);
  // Seller-authored title is the primary headline. Falls back to
  // parcel.description (then region name) when a draft is previewed
  // before the seller has entered a title. The backend enforces non-null
  // title at save time, so the fallback only triggers during in-memory
  // preview while the wizard is still being filled out. Shared resolver
  // keeps the three-level chain in sync with ListingSummaryRow and
  // ParcelInfoPanel; the "(unnamed parcel)" tail stays local because it
  // only makes sense for a preview of a brand-new draft.
  const headline =
    resolveListingHeadline({
      title: auction.title,
      parcelDescription: auction.parcel.description,
      regionName: auction.parcel.regionName,
    }) || "(unnamed parcel)";
  // Parcel description acts as a secondary label when the title has
  // already claimed the headline slot. Elided when equal (so we don't
  // print the same string twice) or when the description is blank.
  const parcelDesc = auction.parcel.description?.trim();
  const subtitle =
    parcelDesc && parcelDesc !== headline ? parcelDesc : null;
  return (
    <article
      className={cn(
        "flex flex-col gap-3 rounded-lg border border-border-subtle bg-surface-raised p-4",
        className,
      )}
    >
      {isPreview && (
        <div className="rounded-lg bg-brand-soft px-3 py-2 text-xs text-brand">
          Preview: this is how your listing will appear to buyers.
        </div>
      )}
      {cover ? (
        /* eslint-disable-next-line @next/next/no-img-element */
        <img
          src={cover}
          alt=""
          className="aspect-square w-full max-w-[700px] mx-auto rounded-lg object-contain bg-bg-subtle"
        />
      ) : null}
      <h3 className="text-base font-bold tracking-tight text-fg">{headline}</h3>
      {subtitle ? (
        <p className="text-xs text-fg-muted">{subtitle}</p>
      ) : null}
      <p className="flex items-center gap-1 text-xs text-fg-muted">
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
        <p className="whitespace-pre-wrap text-sm text-fg">
          {auction.sellerDesc}
        </p>
      )}
      {auction.tags.length > 0 && (
        <ul className="flex flex-wrap gap-1">
          {auction.tags.map((t) => (
            <li
              key={t.code}
              className="inline-flex items-center gap-1 rounded-full bg-bg-hover px-2 py-0.5 text-[11px] font-medium text-fg-muted"
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
      <dt className="text-[11px] font-medium uppercase text-fg-muted">
        {label}
      </dt>
      <dd className="text-sm font-medium text-fg">{value}</dd>
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
