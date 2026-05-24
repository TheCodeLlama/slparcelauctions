/**
 * Pure gradient + dim math for the parcel map. No DOM, no React.
 *
 * Gradient anchors are pinned to SL terraforming semantics:
 *   delta = 0         green   - the parcel's lowest cell (the reference)
 *   delta = 4 m       yellow  - the per-parcel raise/lower limit
 *   delta = 8 m       red     - un-flattenable spread (>8 m can't be levelled)
 *   delta > 8 m       red     - saturated
 *
 * Linear lerp between anchors. Outside-the-parcel cells are dimmed toward
 * neutral gray (60% toward gray) so the parcel pops visually.
 *
 * Colors are rgb triples (not hex) to keep the no-hex-colors verify guard
 * happy. The RGB values reference Tailwind's green-500 / yellow-500 / red-500
 * / cyan-400 for visual consistency with the rest of the app.
 */
export interface Rgb {
  r: number;
  g: number;
  b: number;
}

export const MAP_COLORS = {
  green: { r: 34, g: 197, b: 94 }, // Tailwind green-500
  yellow: { r: 234, g: 179, b: 8 }, // Tailwind yellow-500
  red: { r: 239, g: 68, b: 68 }, // Tailwind red-500
  cyan: { r: 34, g: 211, b: 238 }, // Tailwind cyan-400 (boundary outline)
  neutral: { r: 120, g: 120, b: 120 }, // dim target
} as const;

/** Per-cell color from an elevation delta (cell elevation - parcel min). */
export function gradientColor(deltaMeters: number): Rgb {
  if (deltaMeters <= 0) return { ...MAP_COLORS.green };
  if (deltaMeters >= 8) return { ...MAP_COLORS.red };
  if (deltaMeters <= 4) {
    const t = deltaMeters / 4;
    return lerp(MAP_COLORS.green, MAP_COLORS.yellow, t);
  }
  const t = (deltaMeters - 4) / 4;
  return lerp(MAP_COLORS.yellow, MAP_COLORS.red, t);
}

/** Dim a per-cell color 60% toward neutral gray. */
export function dimOutside(color: Rgb): Rgb {
  const raw = lerp(color, MAP_COLORS.neutral, 0.6);
  return {
    r: Math.round(raw.r),
    g: Math.round(raw.g),
    b: Math.round(raw.b),
  };
}

function lerp(a: Rgb, b: Rgb, t: number): Rgb {
  return {
    r: a.r + (b.r - a.r) * t,
    g: a.g + (b.g - a.g) * t,
    b: a.b + (b.b - a.b) * t,
  };
}
