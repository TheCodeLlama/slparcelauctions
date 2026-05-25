/**
 * Categorical palette for the parcel map's 2D Land Use mode. Each cell is
 * one of five categories; the rgb tuples are pure primaries on pure white
 * so they read as a flat categorical overlay (distinct visual register
 * from the elevation gradient).
 *
 * Numeric values are the wire contract: the bot encodes 4096 bytes with
 * these exact values, the backend stores them raw, and this module is the
 * single point where the frontend turns them into pixels. Do not renumber.
 *
 * Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md
 */
import type { Rgb } from "./colors";

export const LAND_USE_CATEGORY = {
  Other: 0,
  Listed: 1,
  Abandoned: 2,
  ForSale: 3,
  Protected: 4,
} as const;

export type LandUseCategory =
  (typeof LAND_USE_CATEGORY)[keyof typeof LAND_USE_CATEGORY];

export const LAND_USE_COLORS: Readonly<Record<
  "other" | "listed" | "abandoned" | "forSale" | "protected",
  Rgb
>> = {
  other: { r: 255, g: 255, b: 255 },
  listed: { r: 0, g: 255, b: 0 },
  abandoned: { r: 0, g: 0, b: 255 },
  forSale: { r: 255, g: 255, b: 0 },
  protected: { r: 255, g: 0, b: 0 },
} as const;

/**
 * Per-cell color from a raw byte value (0..4). Out-of-range inputs fall
 * back to `other` so a corrupt payload never throws at the render site.
 */
export function landUseCellColor(value: number): Rgb {
  switch (value) {
    case LAND_USE_CATEGORY.Listed:
      return { ...LAND_USE_COLORS.listed };
    case LAND_USE_CATEGORY.Abandoned:
      return { ...LAND_USE_COLORS.abandoned };
    case LAND_USE_CATEGORY.ForSale:
      return { ...LAND_USE_COLORS.forSale };
    case LAND_USE_CATEGORY.Protected:
      return { ...LAND_USE_COLORS.protected };
    case LAND_USE_CATEGORY.Other:
    default:
      return { ...LAND_USE_COLORS.other };
  }
}

/**
 * User-facing label for the hover tooltip in Land Use mode. Plain text;
 * no per-parcel name or owner detail (deferred per spec out-of-scope).
 */
export function landUseCategoryLabel(value: number): string {
  switch (value) {
    case LAND_USE_CATEGORY.Listed:
      return "Listed parcel";
    case LAND_USE_CATEGORY.Abandoned:
      return "Abandoned (claimable)";
    case LAND_USE_CATEGORY.ForSale:
      return "For sale in-world";
    case LAND_USE_CATEGORY.Protected:
      return "Protected (Linden)";
    case LAND_USE_CATEGORY.Other:
    default:
      return "Other (private)";
  }
}
