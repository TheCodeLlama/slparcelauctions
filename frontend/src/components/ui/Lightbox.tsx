/* eslint-disable @next/next/no-img-element -- lightbox displays API-served
 * binary content; next/image's remotePatterns loader is unnecessary. */
"use client";

import { useCallback, useEffect, useRef } from "react";
import { Dialog, DialogPanel } from "@headlessui/react";
import { ChevronLeft, ChevronRight, X } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

/**
 * Minimal image shape the {@link Lightbox} renders. Callers typically hand
 * in an {@code AuctionPhotoDto[]} slice; the lightbox only consumes
 * {@code url} and a stable key, so this narrower interface keeps the
 * component reusable outside the auction domain.
 */
export interface LightboxImage {
  id: number | string;
  url: string;
}

export interface LightboxProps {
  images: LightboxImage[];
  /**
   * Index of the currently-displayed image. {@code null} means closed —
   * Headless UI's {@link Dialog} is unmounted entirely so the backdrop +
   * focus trap don't linger in the DOM.
   */
  openIndex: number | null;
  onClose: () => void;
  /**
   * Called when the user navigates to a different image via ←/→, Home/End,
   * or a thumbnail click. Parent owns {@code openIndex} so navigation
   * survives re-renders without local state divergence.
   */
  onIndexChange: (index: number) => void;
}

/**
 * Full-screen image viewer. Headless UI's {@link Dialog} handles the focus
 * trap, body scroll lock, and portal-to-body (avoids z-index fights with the
 * sticky bid bar). We layer on:
 *
 *   - Keyboard: {@code ←}/{@code →} cycle images, {@code Home}/{@code End}
 *     jump to first / last, {@code Escape} closes (Headless UI default).
 *   - Counter "N / total" top-left.
 *   - Close button top-right.
 *   - Thumbnail strip at the bottom — each thumb is a button, so tab order
 *     is sensible and screen readers see a selectable list.
 *
 * No {@code ResizeObserver}, no {@code useEffect}-driven focus hacks — the
 * Dialog trap does the work. The only hand-rolled behavior is keyboard
 * navigation, attached at the panel level so the key events fire while the
 * dialog owns focus.
 */
export function Lightbox({
  images,
  openIndex,
  onClose,
  onIndexChange,
}: LightboxProps) {
  const isOpen = openIndex !== null;
  const total = images.length;
  const current = isOpen && openIndex < total ? images[openIndex] : null;
  // Ref for the current thumb so {@code scrollIntoView} can keep it visible
  // during keyboard navigation. Set via the ref callback on the matching
  // <button>.
  const activeThumbRef = useRef<HTMLButtonElement | null>(null);

  const go = useCallback(
    (next: number) => {
      if (total === 0) return;
      // Wrap around — ← on the first image goes to the last, and vice versa.
      // Matches the typical gallery UX and keeps the keyboard flow
      // friction-free for single-handed use.
      const wrapped = ((next % total) + total) % total;
      onIndexChange(wrapped);
    },
    [total, onIndexChange],
  );

  // Global keydown listener while open — attaching to an inner div would
  // require the event's focused element to be a descendant, which is not
  // guaranteed across browsers when the user opens via mouse then navigates
  // via keyboard. The Dialog's focus trap keeps focus inside the panel, so
  // a window-level listener cannot fire while an unrelated page input is
  // active. Escape is handled by Headless UI — we only own arrow / Home /
  // End.
  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      switch (e.key) {
        case "ArrowLeft":
          e.preventDefault();
          if (openIndex != null) go(openIndex - 1);
          break;
        case "ArrowRight":
          e.preventDefault();
          if (openIndex != null) go(openIndex + 1);
          break;
        case "Home":
          e.preventDefault();
          go(0);
          break;
        case "End":
          e.preventDefault();
          go(total - 1);
          break;
        default:
          break;
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [isOpen, openIndex, go, total]);

  // Keep the active thumb scrolled into the strip on every index change.
  // jsdom lacks {@code scrollIntoView}, so guard before calling — this path
  // is decoration, not correctness, and tests should not crash on it.
  useEffect(() => {
    if (!isOpen) return;
    const el = activeThumbRef.current;
    if (el && typeof el.scrollIntoView === "function") {
      el.scrollIntoView({
        block: "nearest",
        inline: "center",
        behavior: "smooth",
      });
    }
  }, [openIndex, isOpen]);

  return (
    <Dialog
      open={isOpen}
      onClose={onClose}
      className="relative z-[100]"
      data-testid="lightbox-dialog"
    >
      {/* Dark overlay. Distinct from DialogPanel so clicks on the backdrop
          route through Headless UI's onClose. */}
      <div
        className="fixed inset-0 bg-scrim/90"
        aria-hidden="true"
      />

      <div className="fixed inset-0 flex flex-col">
        <DialogPanel
          data-testid="lightbox"
          className="relative flex h-full w-full flex-col outline-none"
        >
          {/* Counter */}
          {isOpen && current && (
            <div
              className="absolute left-4 top-4 z-10 rounded-full bg-scrim/70 px-3 py-1 text-xs font-medium text-white"
              data-testid="lightbox-counter"
            >
              {openIndex! + 1} / {total}
            </div>
          )}

          {/* Close */}
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            data-testid="lightbox-close"
            className="absolute right-4 top-4 z-10 inline-flex size-10 items-center justify-center rounded-full bg-scrim/70 text-white transition-colors hover:bg-scrim"
          >
            <X className="size-5" aria-hidden="true" />
          </button>

          {/* Main image area. flex-1 so the thumb strip pinned below always
              has its full height; object-contain so tall parcels aren't
              cropped by a 16:9 frame. */}
          <div className="relative flex flex-1 items-center justify-center overflow-hidden p-4 pt-16">
            {current && (
              <img
                key={current.id}
                src={current.url}
                alt=""
                className="max-h-full max-w-full object-contain"
                data-testid="lightbox-image"
              />
            )}

            {/* Prev / Next buttons — hidden when only one image exists so
                we don't imply navigation that wraps back to the same
                frame. */}
            {total > 1 && (
              <>
                <button
                  type="button"
                  onClick={() => openIndex != null && go(openIndex - 1)}
                  aria-label="Previous image"
                  data-testid="lightbox-prev"
                  className="absolute left-4 top-1/2 -translate-y-1/2 inline-flex size-12 items-center justify-center rounded-full bg-scrim/70 text-white transition-colors hover:bg-scrim"
                >
                  <ChevronLeft className="size-6" aria-hidden="true" />
                </button>
                <button
                  type="button"
                  onClick={() => openIndex != null && go(openIndex + 1)}
                  aria-label="Next image"
                  data-testid="lightbox-next"
                  className="absolute right-4 top-1/2 -translate-y-1/2 inline-flex size-12 items-center justify-center rounded-full bg-scrim/70 text-white transition-colors hover:bg-scrim"
                >
                  <ChevronRight className="size-6" aria-hidden="true" />
                </button>
              </>
            )}
          </div>

          {/* Thumb strip — single image cases skip the strip to reclaim
              the vertical space. */}
          {total > 1 && (
            <ul
              className="flex shrink-0 gap-2 overflow-x-auto border-t border-border-subtle bg-scrim/60 p-3"
              data-testid="lightbox-strip"
            >
              {images.map((image, i) => {
                const active = i === openIndex;
                return (
                  <li key={image.id} className="shrink-0">
                    <button
                      type="button"
                      ref={active ? activeThumbRef : undefined}
                      onClick={() => onIndexChange(i)}
                      aria-label={`Image ${i + 1}`}
                      aria-current={active ? "true" : undefined}
                      data-testid={`lightbox-thumb-${i}`}
                      className={cn(
                        "h-16 w-24 overflow-hidden rounded-lg ring-2 transition",
                        active
                          ? "ring-brand"
                          : "ring-transparent hover:ring-border",
                      )}
                    >
                      <img
                        src={image.url}
                        alt=""
                        className="h-full w-full object-cover"
                      />
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </DialogPanel>
      </div>
    </Dialog>
  );
}
