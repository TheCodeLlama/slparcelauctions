"use client";

import { type KeyboardEvent } from "react";

import { cn } from "@/lib/cn";
import { type ParcelMap2DColorMode } from "@/hooks/useParcelMap2DColorMode";

export interface ParcelMap2DColorModeToggleProps {
  mode: ParcelMap2DColorMode;
  onChange: (next: ParcelMap2DColorMode) => void;
  /** When false, the Land Use option is aria-disabled and clicks are suppressed. */
  landUseAvailable?: boolean;
  className?: string;
}

const MODES: ReadonlyArray<{ value: ParcelMap2DColorMode; label: string }> = [
  { value: "elevation", label: "Elevation" },
  { value: "landuse", label: "Land Use" },
];

/**
 * Inline button group for the 2D parcel-map color mode. ARIA radio group
 * pattern (not tabs) -- the user is picking how to color the same scene.
 * Mirrors {@link ParcelMap3DColorModeToggle}. The Land Use option can be
 * disabled for legacy scans whose response carries a null landUseCellsBase64.
 *
 * Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md
 */
export function ParcelMap2DColorModeToggle({
  mode,
  onChange,
  landUseAvailable = true,
  className,
}: ParcelMap2DColorModeToggleProps) {
  const isDisabled = (value: ParcelMap2DColorMode) =>
    value === "landuse" && !landUseAvailable;

  const handleKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    e.preventDefault();
    const idx = MODES.findIndex((m) => m.value === mode);
    if (idx === -1) return;
    const delta = e.key === "ArrowRight" ? 1 : -1;
    const next = MODES[(idx + delta + MODES.length) % MODES.length];
    if (isDisabled(next.value)) return;
    onChange(next.value);
    const group = e.currentTarget.parentElement;
    group?.querySelector<HTMLButtonElement>(`button[data-mode="${next.value}"]`)?.focus();
  };

  return (
    <div
      role="radiogroup"
      aria-label="Color by"
      className={cn(
        "inline-flex gap-1 rounded-md border border-border-subtle bg-surface-raised p-1",
        className,
      )}
    >
      {MODES.map((m) => {
        const selected = mode === m.value;
        const disabled = isDisabled(m.value);
        return (
          <button
            key={m.value}
            type="button"
            role="radio"
            data-mode={m.value}
            aria-checked={selected}
            aria-disabled={disabled || undefined}
            tabIndex={selected ? 0 : -1}
            onClick={() => {
              if (disabled) return;
              onChange(m.value);
            }}
            onKeyDown={handleKeyDown}
            className={cn(
              "px-2 py-1 text-xs font-medium transition-colors rounded",
              "focus:outline-none focus-visible:ring-1 focus-visible:ring-brand",
              selected ? "text-brand" : "text-fg-muted hover:text-fg",
              disabled && "opacity-50 cursor-not-allowed hover:text-fg-muted",
            )}
          >
            {m.label}
          </button>
        );
      })}
    </div>
  );
}
