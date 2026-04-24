"use client";
import {
  Dialog,
  DialogBackdrop,
  DialogPanel,
  DialogTitle,
} from "@headlessui/react";
import { X } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import type { ReactNode } from "react";

export interface BottomSheetProps {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  className?: string;
}

/**
 * Mobile glassmorphic bottom sheet. Built on Headless UI's {@link Dialog}
 * so focus-trap, Escape, and backdrop-click dismissal are provided. Slides
 * up from the bottom edge on open; collapses on close.
 *
 * Intended use is on the mobile browse/search filter surface — the parent
 * renders {@link FilterSidebarContent} (staged mode) inside {@code children}
 * and a primary "Apply filters" button inside {@code footer}. Content slot
 * scrolls independently so long filter lists don't push the footer
 * off-screen.
 */
export function BottomSheet({
  open,
  onClose,
  title,
  children,
  footer,
  className,
}: BottomSheetProps) {
  return (
    <Dialog open={open} onClose={onClose} className="relative z-50">
      <DialogBackdrop
        transition
        className="fixed inset-0 bg-on-surface/30 transition data-[closed]:opacity-0 data-[enter]:duration-200 data-[leave]:duration-150"
      />
      <div className="fixed inset-0 flex items-end justify-center">
        <DialogPanel
          transition
          className={cn(
            "w-full max-h-[85vh] rounded-t-xl",
            "bg-surface-container-lowest/90 backdrop-blur-xl border-t border-outline-variant/30",
            "shadow-elevated",
            "transition data-[closed]:translate-y-full data-[enter]:duration-250 data-[leave]:duration-200",
            className,
          )}
        >
          <div
            className="mx-auto mt-3 mb-2 h-1 w-10 rounded-full bg-outline-variant"
            aria-hidden="true"
          />
          <div className="flex items-center justify-between px-5 py-3">
            <DialogTitle className="text-title-md font-bold">
              {title}
            </DialogTitle>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="rounded p-1 hover:bg-surface-container-high"
            >
              <X className="size-5" aria-hidden="true" />
            </button>
          </div>
          <div className="overflow-y-auto px-5 pb-4 max-h-[70vh]">
            {children}
          </div>
          {footer && (
            <div className="sticky bottom-0 bg-surface-container-lowest/95 backdrop-blur border-t border-outline-variant/30 px-5 py-3">
              {footer}
            </div>
          )}
        </DialogPanel>
      </div>
    </Dialog>
  );
}
