"use client";

import { type KeyboardEvent } from "react";

import { cn } from "@/lib/cn";
import { type ParcelMap3DColorMode } from "@/hooks/useParcelMapColorMode";

interface Props {
  mode: ParcelMap3DColorMode;
  onChange: (next: ParcelMap3DColorMode) => void;
  className?: string;
}

const MODES: ReadonlyArray<{ value: ParcelMap3DColorMode; label: string }> = [
  { value: "elevation", label: "Elevation" },
  { value: "slope", label: "Slope" },
];

/**
 * Inline button group for the 3D parcel-map color mode. ARIA radio group
 * pattern (not tabs) -- the user is picking how to color the same scene, not
 * which scene to render. Pure presentational; the owning component wires
 * {@code mode} and {@code onChange} to {@link useParcelMapColorMode}.
 *
 * Spec: docs/superpowers/specs/2026-05-24-parcel-map-3d-slope-and-solid-design.md
 */
export function ParcelMap3DColorModeToggle({ mode, onChange, className }: Props) {
  const handleKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    e.preventDefault();
    const idx = MODES.findIndex((m) => m.value === mode);
    if (idx === -1) return;
    const delta = e.key === "ArrowRight" ? 1 : -1;
    const next = MODES[(idx + delta + MODES.length) % MODES.length];
    onChange(next.value);
    // Focus follows selection so Arrow keys cycle continuously.
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
        return (
          <button
            key={m.value}
            type="button"
            role="radio"
            data-mode={m.value}
            aria-checked={selected}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(m.value)}
            onKeyDown={handleKeyDown}
            className={cn(
              "px-2 py-1 text-xs font-medium transition-colors",
              selected
                ? "text-brand"
                : "text-fg-muted hover:text-fg",
            )}
          >
            {m.label}
          </button>
        );
      })}
    </div>
  );
}
