/* eslint-disable @next/next/no-img-element -- snapshot + photo bytes are
 * API-served binary content; matches the Avatar / ListingPreviewCard
 * convention where next/image's remotePatterns loader is unnecessary. */
"use client";

import { useState } from "react";
import { Building2 } from "@/components/ui/icons";
import { Lightbox } from "@/components/ui/Lightbox";
import { cn } from "@/lib/cn";
import { apiUrl } from "@/lib/api/url";
import type { AuctionPhotoDto } from "@/types/auction";

/**
 * Hero gallery for the auction detail page.
 *
 * Desktop (`md:`+) layout — asymmetric 4-cell grid, 2 rows tall:
 *   - Cell 1 (3 cols × 2 rows): primary hero photo
 *   - Cells 2 and 3 (1 col × 1 row each): secondary photos
 *   - A "view all N photos" overlay sits on cell 3 when the gallery has
 *     more than 3 photos total; clicking it opens the Lightbox at cell 3's
 *     index.
 *
 * Mobile (<`md:`) — hero image full-width, then a horizontal thumbnail
 * strip. CSS-only responsive: no {@code useMediaQuery}, no hydration flash.
 *
 * Fallback chain (matches ListingSummaryRow / MyListingsTab):
 *   - photos present     → render hero + thumbs
 *   - photos empty, snap → snapshot as solo hero
 *   - nothing at all     → gradient placeholder with region name centred
 *
 * Clicking any photo (hero, secondary, mobile thumb) opens a full-screen
 * {@link Lightbox} at that image's index. The Lightbox itself is portaled
 * to document.body via Headless UI so it sits above the sticky bid bar
 * without a z-index fight.
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
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);

  // If the photos array changes under us (soft navigation to a sibling
  // auction, live seller update), a previously-set lightboxIndex can point
  // past the end of the new array. Derive the effective index at render
  // time rather than syncing via a state-mutating useEffect — the Lightbox
  // treats `null` as closed, so a stale index collapses to a closed dialog
  // without a re-render round-trip (and without tripping the
  // react-hooks/set-state-in-effect lint rule).
  const effectiveLightboxIndex =
    lightboxIndex !== null && lightboxIndex < sorted.length
      ? lightboxIndex
      : null;

  if (sorted.length === 0) {
    if (snapshotUrl) {
      return (
        <div
          className={cn(
            "rounded-lg overflow-hidden h-[320px] md:h-[500px]",
            className,
          )}
          data-testid="auction-hero"
          data-variant="snapshot"
        >
          <img
            src={apiUrl(snapshotUrl) ?? undefined}
            alt={regionName ? `${regionName} snapshot` : "Parcel snapshot"}
            className="h-full w-full object-cover"
          />
        </div>
      );
    }
    return (
      <div
        className={cn(
          "rounded-lg overflow-hidden h-[320px] md:h-[500px]",
          "bg-gradient-to-br from-brand-soft to-bg-hover",
          "flex items-center justify-center text-center",
          className,
        )}
        data-testid="auction-hero"
        data-variant="placeholder"
      >
        <div className="flex flex-col items-center gap-2 text-brand">
          <Building2 className="size-12" aria-hidden="true" />
          <p className="text-base font-bold tracking-tight font-display">
            {regionName ?? "Parcel preview unavailable"}
          </p>
        </div>
      </div>
    );
  }

  const [hero, ...rest] = sorted;
  const secondaries = rest.slice(0, 2);
  const remainingCount = Math.max(0, sorted.length - 3);
  const lightboxImages = sorted.map((p) => ({
    id: p.id,
    url: apiUrl(p.url) ?? p.url,
  }));

  // Single-photo shortcut: skip the asymmetric grid entirely so the hero
  // goes full-width at all breakpoints.
  if (secondaries.length === 0) {
    return (
      <>
        <button
          type="button"
          onClick={() => setLightboxIndex(0)}
          aria-label="Open photo"
          className={cn(
            "block w-full rounded-lg overflow-hidden h-[320px] md:h-[500px] focus:outline-none focus-visible:ring-2 focus-visible:ring-brand",
            className,
          )}
          data-testid="auction-hero"
          data-variant="single"
        >
          <img
            src={apiUrl(hero.url) ?? undefined}
            alt=""
            className="h-full w-full object-cover"
            data-testid="auction-hero-image"
          />
        </button>
        <Lightbox
          images={lightboxImages}
          openIndex={effectiveLightboxIndex}
          onClose={() => setLightboxIndex(null)}
          onIndexChange={setLightboxIndex}
        />
      </>
    );
  }

  return (
    <>
      <div
        className={cn("flex flex-col gap-2", className)}
        data-testid="auction-hero"
        data-variant="gallery"
      >
        {/* Desktop asymmetric grid: hero (3 cols × 2 rows) + two secondaries. */}
        <div className="hidden md:grid md:grid-cols-4 md:grid-rows-2 md:gap-2 md:h-[500px]">
          <button
            type="button"
            onClick={() => setLightboxIndex(0)}
            aria-label="Open photo 1"
            className="md:col-span-3 md:row-span-2 rounded-lg overflow-hidden focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
          >
            <img
              src={apiUrl(hero.url) ?? undefined}
              alt=""
              className="h-full w-full object-cover"
              data-testid="auction-hero-image"
            />
          </button>
          {secondaries.map((photo, i) => {
            // +1 because the hero is index 0.
            const photoIndex = i + 1;
            return (
              <button
                type="button"
                key={photo.id}
                onClick={() => setLightboxIndex(photoIndex)}
                aria-label={`Open photo ${photoIndex + 1}`}
                className="rounded-lg overflow-hidden relative focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                data-testid={`auction-hero-secondary-${i}`}
              >
                <img
                  src={apiUrl(photo.url) ?? undefined}
                  alt=""
                  className="h-full w-full object-cover"
                />
                {/* Overlay "view all" marker on the last secondary cell when
                 * extra photos exist. Clicks still fire the enclosing
                 * button's handler because the overlay is {@code
                 * pointer-events-none} by default; the outer button opens
                 * the Lightbox at this cell's index. */}
                {i === secondaries.length - 1 && remainingCount > 0 && (
                  <span
                    className="absolute inset-0 bg-scrim/60 text-white flex items-center justify-center text-sm font-medium pointer-events-none"
                    data-testid="auction-hero-more-overlay"
                  >
                    +{remainingCount} more
                  </span>
                )}
              </button>
            );
          })}
        </div>

        {/* Mobile stack: hero first, then a horizontal thumb strip. */}
        <div className="md:hidden flex flex-col gap-2">
          <button
            type="button"
            onClick={() => setLightboxIndex(0)}
            aria-label="Open photo 1"
            className="rounded-lg overflow-hidden h-[240px] focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
            data-testid="auction-hero-mobile-primary"
          >
            <img
              src={apiUrl(hero.url) ?? undefined}
              alt=""
              className="h-full w-full object-cover"
            />
          </button>
          {rest.length > 0 && (
            <ul
              className="flex gap-2 overflow-x-auto pb-1"
              data-testid="auction-hero-mobile-strip"
            >
              {rest.map((photo, i) => {
                // +1 because the hero is index 0.
                const photoIndex = i + 1;
                return (
                  <li
                    key={photo.id}
                    className="shrink-0 w-24 h-24 rounded-lg overflow-hidden"
                  >
                    <button
                      type="button"
                      onClick={() => setLightboxIndex(photoIndex)}
                      aria-label={`Open photo ${photoIndex + 1}`}
                      className="h-full w-full focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                    >
                      <img
                        src={apiUrl(photo.url) ?? undefined}
                        alt=""
                        className="h-full w-full object-cover"
                      />
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>
      <Lightbox
        images={lightboxImages}
        openIndex={effectiveLightboxIndex}
        onClose={() => setLightboxIndex(null)}
        onIndexChange={setLightboxIndex}
      />
    </>
  );
}
