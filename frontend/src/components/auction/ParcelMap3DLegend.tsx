"use client";

import { cn } from "@/lib/cn";

export interface ParcelMap3DLegendProps {
  mode: "elevation" | "slope";
  /** Used only in elevation mode; ignored in slope (which is fixed 0..45 deg). */
  maxDelta: number;
  className?: string;
}

const ELEVATION_DESCRIPTION =
  "Color = elevation above the parcel's lowest cell, scaled to the region's relief. Green is the parcel low; red is the region high. The SL terraforming raise/lower limit is 4 m; spread above 8 m can't be levelled.";

const SLOPE_DESCRIPTION =
  "Color = local slope angle, scaled to the 0° (flat, green) to 45° (steep, red) range. Slopes steeper than 45° saturate to red and are effectively unbuildable in SL.";

/**
 * Gradient-bar legend for the 3D parcel-map. Renders a green->red CSS
 * gradient that matches the in-canvas vertex coloring + mode-specific
 * end labels + a descriptive paragraph. Mirrors the 2D elevation legend
 * in shape and uses the same allowlisted inline-style pattern for the
 * gradient bar.
 *
 * Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md
 */
export function ParcelMap3DLegend({ mode, maxDelta, className }: ParcelMap3DLegendProps) {
  const leftLabel = mode === "elevation" ? "0 m" : "0°";
  const rightLabel =
    mode === "elevation" ? `+${Math.round(maxDelta)} m` : "45°";
  const description =
    mode === "elevation" ? ELEVATION_DESCRIPTION : SLOPE_DESCRIPTION;

  return (
    <div
      role="group"
      aria-label="3D map color scale"
      className={cn("flex flex-col gap-1", className)}
    >
      <div className="flex items-center gap-2 text-xs text-fg-muted">
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
      <p className="text-[10px] text-fg-muted">{description}</p>
    </div>
  );
}
