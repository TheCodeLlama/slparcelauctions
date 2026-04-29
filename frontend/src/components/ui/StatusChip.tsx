import { cn } from "@/lib/cn";
import type { ChipTone } from "@/lib/search/status-chip";

export interface StatusChipProps {
  label: string;
  tone: ChipTone;
  className?: string;
}

/**
 * Design-token backed tone palette. Kept flat — no per-tone component — so
 * the ListingCard card-level layout can place the chip absolutely without
 * a wrapping shell.
 */
const TONE_CLASSES: Record<ChipTone, string> = {
  live: "bg-error text-on-error",
  ending_soon: "bg-error text-on-error animate-pulse",
  sold: "bg-tertiary-container text-on-tertiary-container",
  muted: "bg-surface-container-high text-on-surface-variant",
  warning: "bg-error-container text-on-error-container",
};

/**
 * Small uppercase pill used on listing cards + curator rows to communicate
 * auction lifecycle at a glance. Label and tone are the output of
 * {@code deriveStatusChip} — this component is presentation-only.
 */
export function StatusChip({ label, tone, className }: StatusChipProps) {
  return (
    <span
      data-tone={tone}
      className={cn(
        "inline-flex items-center rounded px-2 py-0.5 text-label-sm font-bold uppercase tracking-wider",
        TONE_CLASSES[tone],
        className,
      )}
      aria-label={label}
    >
      {label}
    </span>
  );
}
