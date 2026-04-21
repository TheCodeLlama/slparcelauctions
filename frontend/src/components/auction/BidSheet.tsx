"use client";

import { Dialog, DialogPanel } from "@headlessui/react";
import type { ReactNode } from "react";
import { X } from "@/components/ui/icons";

export interface BidSheetProps {
  isOpen: boolean;
  onClose: () => void;
  children: ReactNode;
}

/**
 * Mobile bottom-sheet that wraps the {@link BidPanel} on small screens.
 * Slides up from the viewport bottom when the caller flips
 * {@code isOpen} — the parent {@code AuctionDetailClient} owns that
 * state and hands {@code onClose} for all dismissal paths (backdrop
 * click, Escape, close button).
 *
 * Uses Headless UI's {@link Dialog} so focus-trap, Escape handling, and
 * backdrop-click dismissal come for free. Swipe-to-dismiss is
 * intentionally absent per spec §11 — no gesture listeners, no
 * framer-motion drag wiring. The visual drag handle at the top is
 * purely decorative.
 *
 * See spec §7 (mobile pattern) and §11 ({@code BidSheet} details).
 */
export function BidSheet({ isOpen, onClose, children }: BidSheetProps) {
  return (
    <Dialog
      open={isOpen}
      onClose={onClose}
      className="relative z-50"
      data-testid="bid-sheet-dialog"
    >
      {/* Backdrop. Headless UI routes clicks on this element through
          {@code onClose} because the {@link DialogPanel} below does not
          enclose it. */}
      <div
        className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
        aria-hidden="true"
      />

      <div className="fixed inset-x-0 bottom-0 flex justify-center">
        <DialogPanel
          data-testid="bid-sheet"
          className="w-full max-w-xl max-h-[85vh] overflow-y-auto rounded-t-xl bg-surface-container-low p-6 pt-4 shadow-elevated"
        >
          {/* Decorative drag handle. Not a {@code button}, no
              {@code onClick}, no gesture listeners — purely visual
              affordance per spec §11. */}
          <div
            data-testid="bid-sheet-drag-handle"
            aria-hidden="true"
            className="mx-auto mb-3 h-[3px] w-[30px] rounded-full bg-outline-variant"
          />

          <div className="flex items-center justify-end">
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              data-testid="bid-sheet-close"
              className="inline-flex size-9 items-center justify-center rounded-full text-on-surface-variant transition-colors hover:bg-surface-container-high"
            >
              <X className="size-5" aria-hidden="true" />
            </button>
          </div>

          <div data-testid="bid-sheet-body" className="flex flex-col gap-4">
            {children}
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
