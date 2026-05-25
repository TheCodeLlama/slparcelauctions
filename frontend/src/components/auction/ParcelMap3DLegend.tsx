"use client";

import { cn } from "@/lib/cn";

export interface ParcelMap3DLegendProps {
  mode: "elevation" | "slope";
  /** Used only in elevation mode; ignored in slope (which is fixed 0..45 deg). */
  maxDelta: number;
  className?: string;
}

/**
 * Gradient-bar legend for the 3D parcel-map. Renders a green->red CSS
 * gradient that matches the in-canvas vertex coloring + the mode-specific
 * end labels. Mirrors the existing 2D elevation legend in shape and uses
 * the same allowlisted inline-style pattern for the gradient bar.
 *
 * Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md
 */
export function ParcelMap3DLegend({ mode, maxDelta, className }: ParcelMap3DLegendProps) {
  const leftLabel = mode === "elevation" ? "0 m" : "0°";
  const rightLabel =
    mode === "elevation" ? `+${Math.round(maxDelta)} m` : "45°";

  return (
    <div
      role="group"
      aria-label="3D map color scale"
      className={cn("flex items-center gap-2 text-xs text-fg-muted", className)}
    >
      <span>{leftLabel}</span>
      <div
        className="h-2 flex-1 rounded-sm"
        // Inline style: gradient is data-driven, not a static theme color.
        // Allowlisted in scripts/verify-no-inline-styles.sh.
        style={{
          background:
            "linear-gradient(to right, rgb(34, 197, 94), rgb(239, 68, 68))",
        }}
      />
      <span>{rightLabel}</span>
    </div>
  );
}
