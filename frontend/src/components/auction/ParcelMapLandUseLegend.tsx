"use client";

import { cn } from "@/lib/cn";
import { LAND_USE_COLORS } from "@/lib/parcelMap/landUseColors";

const SWATCHES: ReadonlyArray<{ label: string; rgb: { r: number; g: number; b: number } }> = [
  { label: "Listed", rgb: LAND_USE_COLORS.listed },
  { label: "Abandoned", rgb: LAND_USE_COLORS.abandoned },
  { label: "For Sale", rgb: LAND_USE_COLORS.forSale },
  { label: "Protected", rgb: LAND_USE_COLORS.protected },
];

export interface ParcelMapLandUseLegendProps {
  className?: string;
}

/**
 * Four-swatch legend for the 2D parcel-map's Land Use mode. Pure
 * presentational; the color values come from {@link LAND_USE_COLORS} so
 * the legend swatches always match the rendered cells.
 *
 * Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md
 */
export function ParcelMapLandUseLegend({ className }: ParcelMapLandUseLegendProps) {
  return (
    <div
      role="group"
      aria-label="Land Use legend"
      className={cn("flex flex-wrap items-center gap-3 text-xs text-fg-muted", className)}
    >
      {SWATCHES.map((s) => (
        <span key={s.label} className="inline-flex items-center gap-1">
          <span
            className="h-3 w-3 border border-border-subtle"
            // Inline style: rgb tuple is from the data palette, not a theme
            // color. Allowlisted in scripts/verify-no-inline-styles.sh.
            style={{ background: `rgb(${s.rgb.r}, ${s.rgb.g}, ${s.rgb.b})` }}
            aria-hidden="true"
          />
          <span>{s.label}</span>
        </span>
      ))}
    </div>
  );
}
