/* eslint-disable @next/next/no-img-element -- snapshot + photo bytes are
 * API-served binary content; matches the Avatar / ListingPreviewCard
 * convention where next/image's remotePatterns loader is unnecessary. */
"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from "react";
import { Building2 } from "@/components/ui/icons";
import { Lightbox } from "@/components/ui/Lightbox";
import { cn } from "@/lib/cn";
import { apiUrl } from "@/lib/api/url";
import type { AuctionPhotoDto } from "@/types/auction";

/**
 * Hero gallery for the auction detail page.
 *
 * Layout — single full-width hero image plus a horizontal thumbnail strip
 * directly beneath it. Stays inside the existing {@code lg:col-span-8}
 * column. Mobile and desktop share the same structure; only the hero height
 * and thumb sizes step up at the {@code md:} breakpoint.
 *
 * Fallback chain (matches ListingSummaryRow / MyListingsTab):
 *   - photos present     → render hero + thumbs
 *   - photos empty, snap → snapshot as solo hero
 *   - nothing at all     → gradient placeholder with region name centred
 *
 * Interaction model (multi-photo branch only):
 *   - Click a thumb     → swap the hero; no Lightbox.
 *   - Click the hero    → open the Lightbox at the currently-selected index.
 *   - {@code ←} / {@code →} (page-level) → previous / next; wraps at edges.
 *   - Touch swipe hero  → swipe-left = next, swipe-right = previous.
 *   - Touch tap hero    → open the Lightbox.
 *   - Arrow keys are ignored while the Lightbox is open (the Lightbox owns
 *     its own keybindings) and while focus is in a form input or any
 *     modifier key is held.
 *
 * Clicking any photo opens a full-screen {@link Lightbox} at that image's
 * index. The Lightbox itself is portaled to document.body via Headless UI
 * so it sits above the sticky bid bar without a z-index fight.
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

  const [hero] = sorted;
  const lightboxImages = sorted.map((p) => ({
    id: p.publicId,
    url: apiUrl(p.url) ?? p.url,
  }));

  // Single-photo shortcut: skip the thumb strip entirely so the hero goes
  // full-width at all breakpoints with no trailing empty row beneath it.
  if (sorted.length === 1) {
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
    <MultiPhotoGallery
      sorted={sorted}
      lightboxImages={lightboxImages}
      lightboxIndex={lightboxIndex}
      setLightboxIndex={setLightboxIndex}
      effectiveLightboxIndex={effectiveLightboxIndex}
      className={className}
    />
  );
}

/* --------------------------------------------------------------------- */
/* Multi-photo branch                                                    */
/* --------------------------------------------------------------------- */

interface MultiPhotoGalleryProps {
  sorted: AuctionPhotoDto[];
  lightboxImages: { id: string; url: string }[];
  lightboxIndex: number | null;
  setLightboxIndex: (i: number | null) => void;
  effectiveLightboxIndex: number | null;
  className?: string;
}

function MultiPhotoGallery({
  sorted,
  lightboxImages,
  lightboxIndex,
  setLightboxIndex,
  effectiveLightboxIndex,
  className,
}: MultiPhotoGalleryProps) {
  const [selectedIndex, setSelectedIndex] = useState(0);

  // Defensive clamp: if the photos array shrinks under us (soft nav, live
  // seller update), a stale selectedIndex can point past the end. Derive
  // the effective index at render time so we never reach into sorted[]
  // with an out-of-bounds value.
  const effectiveSelectedIndex =
    selectedIndex < sorted.length ? selectedIndex : 0;

  const goPrev = useCallback(() => {
    setSelectedIndex(
      (current) => (current - 1 + sorted.length) % sorted.length,
    );
  }, [sorted.length]);

  const goNext = useCallback(() => {
    setSelectedIndex((current) => (current + 1) % sorted.length);
  }, [sorted.length]);

  const openLightboxHere = useCallback(() => {
    setLightboxIndex(effectiveSelectedIndex);
  }, [effectiveSelectedIndex, setLightboxIndex]);

  // Window-level keydown listener for ← / →. Guards:
  //   - Lightbox open  → Lightbox owns the arrow keys; bail.
  //   - Modifier held  → respect browser hotkeys (back / forward, etc.).
  //   - Form input     → don't steal typing context.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
      if (lightboxIndex !== null) return;
      if (e.metaKey || e.ctrlKey || e.altKey || e.shiftKey) return;
      const target = e.target as HTMLElement | null;
      if (target) {
        const tag = target.tagName;
        if (
          tag === "INPUT" ||
          tag === "TEXTAREA" ||
          tag === "SELECT" ||
          target.isContentEditable
        ) {
          return;
        }
      }
      e.preventDefault();
      if (e.key === "ArrowLeft") {
        goPrev();
      } else {
        goNext();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [goPrev, goNext, lightboxIndex]);

  const heroPhoto = sorted[effectiveSelectedIndex];

  return (
    <>
      <div
        className={cn("flex flex-col gap-2", className)}
        data-testid="auction-hero"
        data-variant="gallery"
      >
        <HeroImage
          photo={heroPhoto}
          index={effectiveSelectedIndex}
          total={sorted.length}
          onTap={openLightboxHere}
          onSwipeLeft={goNext}
          onSwipeRight={goPrev}
        />
        <ThumbStrip
          photos={sorted}
          selectedIndex={effectiveSelectedIndex}
          onSelect={setSelectedIndex}
        />
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

/* --------------------------------------------------------------------- */
/* Subcomponents                                                         */
/* --------------------------------------------------------------------- */

interface HeroImageProps {
  photo: AuctionPhotoDto;
  index: number;
  total: number;
  onTap: () => void;
  onSwipeLeft: () => void;
  onSwipeRight: () => void;
}

// Touch-swipe heuristic thresholds. Numbers chosen for the spec's
// {@code 30px horizontal / 30px vertical / 500ms} rule — keeps a deliberate
// horizontal flick distinct from a vertical page scroll or a slow drag.
const SWIPE_MIN_HORIZONTAL_PX = 30;
const SWIPE_MAX_VERTICAL_PX = 30;
const SWIPE_MAX_DURATION_MS = 500;

function HeroImage({
  photo,
  index,
  total,
  onTap,
  onSwipeLeft,
  onSwipeRight,
}: HeroImageProps) {
  // Pointer-down state — captured at the start of every press; cleared on
  // pointerup. A null value during pointerup means we never saw the
  // corresponding pointerdown (e.g. focus came in from outside the
  // element) so we treat that as a no-op rather than a tap.
  const pointerStart = useRef<{ x: number; y: number; t: number } | null>(
    null,
  );

  const handlePointerDown = (e: ReactPointerEvent<HTMLButtonElement>) => {
    pointerStart.current = {
      x: e.clientX,
      y: e.clientY,
      t: Date.now(),
    };
  };

  const handlePointerUp = (e: ReactPointerEvent<HTMLButtonElement>) => {
    const start = pointerStart.current;
    pointerStart.current = null;
    if (!start) {
      // Defensive: no recorded press — open the Lightbox via the standard
      // click path (the synthetic click will follow this pointerup).
      return;
    }
    const dx = e.clientX - start.x;
    const dy = e.clientY - start.y;
    const elapsed = Date.now() - start.t;
    const isSwipe =
      Math.abs(dx) > SWIPE_MIN_HORIZONTAL_PX &&
      Math.abs(dy) < SWIPE_MAX_VERTICAL_PX &&
      elapsed < SWIPE_MAX_DURATION_MS;
    if (isSwipe) {
      // Prevent the synthetic click that follows a pointerup so the
      // Lightbox does not also open on a deliberate swipe.
      e.preventDefault();
      if (dx < 0) {
        onSwipeLeft();
      } else {
        onSwipeRight();
      }
      return;
    }
    // Not a swipe — fall through to onTap. We invoke onTap directly rather
    // than relying on the button's onClick because preventDefault on
    // pointerup can suppress the click event on some browsers.
    onTap();
  };

  return (
    <button
      type="button"
      onPointerDown={handlePointerDown}
      onPointerUp={handlePointerUp}
      aria-label="Open photo"
      className="relative block w-full rounded-lg overflow-hidden h-[240px] md:h-[380px] focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      data-testid="auction-hero-main"
    >
      <img
        src={apiUrl(photo.url) ?? undefined}
        alt=""
        className="h-full w-full object-cover"
        data-testid="auction-hero-image"
      />
      <span
        className="absolute bottom-2 right-2 rounded-full bg-scrim/70 px-2.5 py-1 text-xs font-medium text-white"
        data-testid="auction-hero-counter"
      >
        {index + 1} / {total}
      </span>
    </button>
  );
}

interface ThumbStripProps {
  photos: AuctionPhotoDto[];
  selectedIndex: number;
  onSelect: (index: number) => void;
}

function ThumbStrip({ photos, selectedIndex, onSelect }: ThumbStripProps) {
  const scrollContainerRef = useRef<HTMLDivElement | null>(null);
  const thumbRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const [hasLeftOverflow, setHasLeftOverflow] = useState(false);
  const [hasRightOverflow, setHasRightOverflow] = useState(false);

  const recomputeOverflow = useCallback(() => {
    const el = scrollContainerRef.current;
    if (!el) return;
    setHasLeftOverflow(el.scrollLeft > 0);
    setHasRightOverflow(
      el.scrollWidth - el.clientWidth - el.scrollLeft > 0,
    );
  }, []);

  // Hook the scroll event + recompute on mount and whenever the photo set
  // or active thumb changes (the latter because auto-scroll-into-view can
  // shift scrollLeft).
  useEffect(() => {
    const el = scrollContainerRef.current;
    if (!el) return;
    recomputeOverflow();
    el.addEventListener("scroll", recomputeOverflow, { passive: true });
    return () => el.removeEventListener("scroll", recomputeOverflow);
  }, [recomputeOverflow, photos.length, selectedIndex]);

  // Auto-scroll the active thumb into view whenever selectedIndex changes.
  // Click handlers also trigger this — the active thumb is already visible
  // in that case, so scrollIntoView is a no-op and the cost is negligible.
  // jsdom lacks scrollIntoView, so guard before calling.
  useEffect(() => {
    const el = thumbRefs.current[selectedIndex];
    if (el && typeof el.scrollIntoView === "function") {
      el.scrollIntoView({
        behavior: "smooth",
        block: "nearest",
        inline: "center",
      });
    }
  }, [selectedIndex]);

  return (
    <div className="relative">
      <div
        ref={scrollContainerRef}
        className="flex gap-2 overflow-x-auto pb-1"
        data-testid="auction-hero-thumb-strip"
      >
        {photos.map((photo, i) => {
          const active = i === selectedIndex;
          return (
            <button
              key={photo.publicId}
              ref={(node) => {
                thumbRefs.current[i] = node;
              }}
              type="button"
              onClick={() => onSelect(i)}
              aria-label={`Show photo ${i + 1}`}
              aria-current={active ? "true" : undefined}
              data-testid={`auction-hero-thumb-${i}`}
              className={cn(
                "shrink-0 w-14 h-14 md:w-16 md:h-16 rounded-lg overflow-hidden focus:outline-none focus-visible:ring-2 focus-visible:ring-brand",
                active && "outline-2 outline-brand outline-offset-2",
              )}
            >
              <img
                src={apiUrl(photo.url) ?? undefined}
                alt=""
                className="h-full w-full object-cover"
              />
            </button>
          );
        })}
      </div>
      {hasLeftOverflow && (
        <div
          aria-hidden="true"
          data-testid="auction-hero-thumb-fade-left"
          className="pointer-events-none absolute inset-y-0 left-0 w-8 bg-gradient-to-r from-bg to-transparent"
        />
      )}
      {hasRightOverflow && (
        <div
          aria-hidden="true"
          data-testid="auction-hero-thumb-fade-right"
          className="pointer-events-none absolute inset-y-0 right-0 w-8 bg-gradient-to-l from-bg to-transparent"
        />
      )}
    </div>
  );
}
