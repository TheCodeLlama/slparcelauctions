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

export interface DrawerProps {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  className?: string;
}

/**
 * Desktop glass drawer. Right-anchored sibling of
 * {@link BottomSheet} — built on the same Headless UI {@link Dialog} shape
 * so focus-trap, Escape, and backdrop-click dismissal are provided out of
 * the box.
 *
 * <p>Desktop width is 480px; on narrow viewports the panel stretches to
 * the full viewport width (the Curator Tray swaps to {@code BottomSheet}
 * below the {@code md} breakpoint via {@code useMediaQuery}, so the full-
 * width mobile fallback here is a safety net rather than the primary
 * shell).
 */
export function Drawer({
  open,
  onClose,
  title,
  children,
  footer,
  className,
}: DrawerProps) {
  return (
    <Dialog open={open} onClose={onClose} className="relative z-50">
      <DialogBackdrop
        transition
        className="fixed inset-0 bg-on-surface/30 transition data-[closed]:opacity-0 data-[enter]:duration-200 data-[leave]:duration-150"
      />
      <div className="fixed inset-0 flex justify-end">
        <DialogPanel
          transition
          className={cn(
            "flex h-full w-full max-w-[480px] flex-col",
            "bg-surface-container-lowest/90 backdrop-blur-xl border-l border-outline-variant/30",
            "shadow-elevated",
            "transition data-[closed]:translate-x-full data-[enter]:duration-250 data-[leave]:duration-200",
            className,
          )}
        >
          <div className="flex items-center justify-between px-5 py-4 border-b border-outline-variant/30">
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
          <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
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
