"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { cn } from "@/lib/cn";

export interface ModalProps {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
}

/**
 * Centered modal surface used by the wallet flows (terms, deposit
 * instructions, withdraw, pay-penalty). Plain-React implementation —
 * Escape key and backdrop click close the modal, and the first
 * focusable child receives focus on open. For more sophisticated needs
 * (focus-trap loops, transitions) prefer Headless UI's {@code Dialog}
 * directly; this primitive is intended for the simple "open / show
 * content / close" pattern.
 *
 * The surface uses {@code bg-surface-container} so it adapts to the
 * active theme; the backdrop uses {@code bg-scrim/40} for the standard
 * Material-3 scrim treatment.
 */
export function Modal({
  open,
  title,
  onClose,
  children,
  footer,
}: ModalProps) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  useEffect(() => {
    if (open && containerRef.current) {
      const focusable = containerRef.current.querySelector<HTMLElement>(
        "button, [href], input, select, textarea, [tabindex]:not([tabindex='-1'])",
      );
      focusable?.focus();
    }
  }, [open]);

  if (!open) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={title}
      className={cn(
        "fixed inset-0 z-50 flex items-center justify-center p-4",
        "bg-scrim/40",
      )}
      onClick={onClose}
    >
      <div
        ref={containerRef}
        className={cn(
          "bg-surface-container rounded-2xl p-6 max-w-md w-full",
          "max-h-[80vh] overflow-y-auto",
          "shadow-elevated",
        )}
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-lg font-semibold text-on-surface mb-4">{title}</h3>
        <div className="text-sm text-on-surface space-y-3">{children}</div>
        {footer && <div className="mt-4 flex justify-end gap-2">{footer}</div>}
      </div>
    </div>
  );
}
