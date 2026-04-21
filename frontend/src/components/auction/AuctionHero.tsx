/* eslint-disable @next/next/no-img-element -- snapshot + photo bytes are
 * API-served binary content; matches the Avatar / ListingPreviewCard
 * convention where next/image's remotePatterns loader is unnecessary. */
import { Building2 } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import type { AuctionPhotoDto } from "@/types/auction";

/**
 * Hero gallery for the auction detail page.
 *
 * Desktop (`md:`+) layout — asymmetric 4-cell grid, 2 rows tall:
 *   - Cell 1 (3 cols × 2 rows): primary hero photo
 *   - Cells 2 and 3 (1 col × 1 row each): secondary photos
 *   - A "view all N photos" overlay sits on cell 3 when the gallery has
 *     more than 3 photos total. Photo modal is a stretch goal — not in
 *     sub-spec 2 scope, so the overlay is a non-interactive hint.
 *
 * Mobile (<`md:`) — hero image full-width, then a horizontal thumbnail
 * strip. CSS-only responsive: no {@code useMediaQuery}, no hydration flash.
 *
 * Fallback chain (matches ListingSummaryRow / MyListingsTab):
 *   - photos present     → render hero + thumbs
 *   - photos empty, snap → snapshot as solo hero
 *   - nothing at all     → gradient placeholder with region name centred
 */
interface Props {
  photos: AuctionPhotoDto[];
  snapshotUrl?: string | null;
  regionName?: string;
  className?: string;
}

const SORT_BY_ORDER = (a: AuctionPhotoDto, b: AuctionPhotoDto) =>
  a.sortOrder - b.sortOrder;

export function AuctionHero({
  photos,
  snapshotUrl,
  regionName,
  className,
}: Props) {
  const sorted = [...photos].sort(SORT_BY_ORDER);

  if (sorted.length === 0) {
    if (snapshotUrl) {
      return (
        <div
          className={cn(
            "rounded-default overflow-hidden h-[320px] md:h-[500px]",
            className,
          )}
          data-testid="auction-hero"
          data-variant="snapshot"
        >
          <img
            src={snapshotUrl}
            alt={regionName ? `${regionName} snapshot` : "Parcel snapshot"}
            className="h-full w-full object-cover"
          />
        </div>
      );
    }
    return (
      <div
        className={cn(
          "rounded-default overflow-hidden h-[320px] md:h-[500px]",
          "bg-gradient-to-br from-primary-container to-surface-container-high",
          "flex items-center justify-center text-center",
          className,
        )}
        data-testid="auction-hero"
        data-variant="placeholder"
      >
        <div className="flex flex-col items-center gap-2 text-on-primary-container">
          <Building2 className="size-12" aria-hidden="true" />
          <p className="text-title-lg font-display font-bold">
            {regionName ?? "Parcel preview unavailable"}
          </p>
        </div>
      </div>
    );
  }

  const [hero, ...rest] = sorted;
  const secondaries = rest.slice(0, 2);
  const remainingCount = Math.max(0, sorted.length - 3);

  // Single-photo shortcut: skip the asymmetric grid entirely so the hero
  // goes full-width at all breakpoints.
  if (secondaries.length === 0) {
    return (
      <div
        className={cn(
          "rounded-default overflow-hidden h-[320px] md:h-[500px]",
          className,
        )}
        data-testid="auction-hero"
        data-variant="single"
      >
        <img
          src={hero.url}
          alt=""
          className="h-full w-full object-cover"
          data-testid="auction-hero-image"
        />
      </div>
    );
  }

  return (
    <div
      className={cn("flex flex-col gap-2", className)}
      data-testid="auction-hero"
      data-variant="gallery"
    >
      {/* Desktop asymmetric grid: hero (3 cols × 2 rows) + two secondaries. */}
      <div className="hidden md:grid md:grid-cols-4 md:grid-rows-2 md:gap-2 md:h-[500px]">
        <div className="md:col-span-3 md:row-span-2 rounded-default overflow-hidden">
          <img
            src={hero.url}
            alt=""
            className="h-full w-full object-cover"
            data-testid="auction-hero-image"
          />
        </div>
        {secondaries.map((photo, i) => (
          <div
            key={photo.id}
            className="rounded-default overflow-hidden relative"
            data-testid={`auction-hero-secondary-${i}`}
          >
            <img
              src={photo.url}
              alt=""
              className="h-full w-full object-cover"
            />
            {/* Overlay "view all" marker on the last secondary cell when
             * extra photos exist. Non-interactive — a modal is a stretch
             * goal outside sub-spec 2 scope. */}
            {i === secondaries.length - 1 && remainingCount > 0 && (
              <div
                className="absolute inset-0 bg-scrim/60 text-on-surface-inverse flex items-center justify-center text-label-lg font-medium"
                data-testid="auction-hero-more-overlay"
              >
                +{remainingCount} more
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Mobile stack: hero first, then a horizontal thumb strip. */}
      <div className="md:hidden flex flex-col gap-2">
        <div
          className="rounded-default overflow-hidden h-[240px]"
          data-testid="auction-hero-mobile-primary"
        >
          <img
            src={hero.url}
            alt=""
            className="h-full w-full object-cover"
          />
        </div>
        {rest.length > 0 && (
          <ul
            className="flex gap-2 overflow-x-auto pb-1"
            data-testid="auction-hero-mobile-strip"
          >
            {rest.map((photo) => (
              <li
                key={photo.id}
                className="shrink-0 w-24 h-24 rounded-default overflow-hidden"
              >
                <img
                  src={photo.url}
                  alt=""
                  className="h-full w-full object-cover"
                />
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
