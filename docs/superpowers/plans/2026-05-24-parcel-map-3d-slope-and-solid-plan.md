# Parcel Map 3D Slope Mode + Solid Look Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an inline slope-shaded color mode (flat = green, 45° = red) toggleable inside the 3D parcel map, plus extruded region walls and a floor plane so the terrain reads as a solid chunk of land. Simplify the shared green/red gradient from 3-stop to 2-stop across both 2D and 3D views.

**Architecture:** New localStorage-backed `useParcelMapColorMode` hook and `ParcelMap3DColorModeToggle` presentational component drive a color mode (`"elevation" | "slope"`) into `buildHeightfieldGeometry`. The geometry helper gains a slope grid computed via closed-form finite-difference, plus extruded region-perimeter walls and a bottom floor quad. The shared `colors.ts` helper rewrites `gradientColor` as a 2-stop lerp and adds `slopeColor`; the 2D `ParcelMapLegend` updates in lockstep.

**Tech Stack:** Next.js 16 (App Router) + React 19 + TypeScript 5 + Tailwind CSS 4 + Three.js + `@react-three/fiber` + `@react-three/drei` + Vitest.

**Branch:** `feat/parcel-map-3d-slope-and-solid` (off latest `dev`, HEAD `3c9cdc9b` is the spec commit).

**Spec:** `docs/superpowers/specs/2026-05-24-parcel-map-3d-slope-and-solid-design.md`

**Partially addresses:** GitHub issue #414 (adds region walls; the parcel-specific walls + water plane + snapshot ground texture remain out of scope).

---

## Conventions every task must honor

- No emojis anywhere.
- No AI/Claude/Anthropic attribution in commits or PR bodies (no `Co-Authored-By` trailers, no AI footers).
- No em-dashes in user-visible copy, commit messages, PR titles, or PR bodies.
- Push commits before requesting review.
- Use `Edit` not `Write` for existing files.
- Per-task verification: `npm test` AND `npm run build` AND `npm run verify` (Vitest does not type-check; `npm run build` is the tsc gate).
- Semantic Tailwind tokens only. No `dark:` variants. No `#hex` literals in className strings (use `rgb(...)` or semantic tokens). `rgb(...)` inside JSX prop values is fine.
- READ `frontend/AGENTS.md` before touching any frontend code (Next.js 16 / React 19 differ from older versions).
- Existing helpers used by both 2D and 3D views (`useParcelScan`, `encoding.ts`, the surviving parts of `colors.ts`) must NOT be duplicated.
- `useParcelMapView.ts`, `ParcelMapTabs.tsx`, `ParcelMap3DSkeleton.tsx`, and all backend code are NOT touched by this plan.

---

## File map

**New files:**

| File | Responsibility |
|---|---|
| `frontend/src/hooks/useParcelMapColorMode.ts` | localStorage-backed `"elevation" \| "slope"` state, two-phase mount. |
| `frontend/src/hooks/useParcelMapColorMode.test.tsx` | Default, persistence, junk filter, SSR safety. |
| `frontend/src/components/auction/ParcelMap3DColorModeToggle.tsx` | Pure presentational inline button group, ARIA radio group. |
| `frontend/src/components/auction/ParcelMap3DColorModeToggle.test.tsx` | ARIA roles, aria-checked, click handler, arrow-key cycle. |

**Modified files:**

| File | Change |
|---|---|
| `frontend/src/lib/parcelMap/colors.ts` | Drop `MAP_COLORS.yellow`; rewrite `gradientColor` as 2-stop; add `slopeColor`; export `lerp` so external callers (test fixtures) can verify midpoints if needed (or leave inline — see Task 1). |
| `frontend/src/lib/parcelMap/colors.test.ts` | Update existing 3-stop test cases to 2-stop midpoints; add `slopeColor` cases. |
| `frontend/src/components/auction/ParcelMap.tsx` | `ParcelMapLegend`: 2-stop CSS gradient; drop the `+4 m` middle axis label. Explainer text stays. |
| `frontend/src/lib/parcelMap3D/geometry.ts` | Add `computeSlopeGrid`; extend `buildHeightfieldGeometry(upsampled, parcelMin, floorY, mode, slopeGrid)`; emit walls + floor inline. |
| `frontend/src/lib/parcelMap3D/geometry.test.ts` | Update existing `buildHeightfieldGeometry` tests for new signature; add slope-grid + wall/floor + mode-swap tests. |
| `frontend/src/components/auction/ParcelMap3D.tsx` | Call `useParcelMapColorMode`; thread `colorMode`, `floorY`, `slopeGrid` into `buildHeightfieldGeometry` memo; render `<ParcelMap3DColorModeToggle>` inside scene wrapper gated on data loaded. |
| `frontend/src/components/auction/ParcelMap3D.test.tsx` | Add case: toggling color mode rebuilds geometry with the new mode. |

**Untouched:** `useParcelMapView.ts`, `ParcelMapTabs.tsx`, `ParcelMap3DSkeleton.tsx`, all backend code, all bot code, all LSL.

---

## Pre-resolved gotchas (do NOT let a reviewer "correct" these back)

1. **2-stop gradient is shared across 2D and 3D.** `gradientColor(deltaMeters)` is `lerp(green, red, clamp(deltaMeters / 8, 0, 1))`. No yellow stop. The 2D map's legend bar simplifies to 2 stops, and the `+4 m` label is removed. `MAP_COLORS.yellow` is deleted. The user explicitly confirmed the shared-helper change applies to both 2D and 3D.

2. **`computeParcelStats` still consumes the RAW 64x64 grid**, not the upsampled grid. Unchanged from the prior 3D PR. Don't let this get "fixed."

3. **Slope from finite difference, NOT from `computeVertexNormals` output.** `computeSlopeGrid(upsampled)` is a closed-form finite-difference helper, deterministic, returns `Float32Array(UPSAMPLED_GRID²)`. Edges use clamp-to-edge one-sided differences.

4. **45° not 90° for slope-red saturation.** `t = clamp(slopeRad / (Math.PI / 4), 0, 1)`. The 0..π/2 range was explicitly rejected.

5. **Walls use OWN vertices**, NOT shared with the top mesh. Sharing would cause `computeVertexNormals` to average top-face and wall-face normals at the perimeter, producing weird normals at the edge. Each wall gets 2 × 129 = 258 fresh vertices per side (top edge + bottom edge), 4 sides = 1,032 wall vertices.

6. **Wall coloring matches the top vertex at the same X/Z, multiplied by 0.55**, for BOTH top and bottom edges of the wall. The wall reads as a vertically-uniform dimmed strip of the same material. When colorMode flips, walls recolor in lockstep with the top surface.

7. **Triangle winding (CRITICAL):**
   - Top mesh: CCW from +Y so normals point +Y. (Already correct post-normals-fix PR.)
   - Walls: CCW from OUTSIDE the region. North wall normal = `+Z`, south = `-Z`, east = `+X`, west = `-X`.
   - Floor: CCW from BELOW (-Y looking +Y) so face normal points `-Y`.
   - Tests assert the average face normal per wall and the floor normal.

8. **Floor color is a fixed earth tone `rgb(60, 50, 40)`**, not derived from terrain. Defensive coverage for low-orbit visibility.

9. **ARIA radio-group pattern (NOT tabs).** Toggle is `role="radiogroup"` + 2× `role="radio"` + `aria-checked` + `aria-label="Color by"`. Not `role="tablist"`. The user is choosing how to color the same scene, not which scene to render.

10. **localStorage key + default:** `slpa:parcel-map:3d-color`, default `"elevation"`. Two-phase mount (constant default initial state + `useEffect` read) — same pattern as `useParcelMapView`, do NOT use a lazy `useState` initializer (would cause hydration mismatch). Use the `/* eslint-disable react-hooks/set-state-in-effect */` block to suppress the lint rule (as in `useParcelMapView.ts`).

11. **Toggle hidden during skeleton state.** `<ParcelMap3DColorModeToggle>` only renders in the success-path render branch (data loaded, WebGL available). Not during skeleton, not during fallback, not on `data === null`.

12. **`buildHeightfieldGeometry` signature changes to FIVE args**: `(upsampled, parcelMin, floorY, mode, slopeGrid)`. `slopeGrid` is always required even for elevation mode — keeps the call site predictable, avoids optional-arg ambiguity, slope computation is cheap.

---

## Task 1: colors.ts rewrite + ParcelMapLegend update

This task ships an atomic 2D change: the color helper switches from 3-stop to 2-stop, and the 2D map's legend reflects the new gradient. Both sides of the change land in one commit so the 2D map never renders olive tones with a "yellow at +4 m" label.

**Files:**
- Modify: `frontend/src/lib/parcelMap/colors.ts`
- Modify: `frontend/src/lib/parcelMap/colors.test.ts`
- Modify: `frontend/src/components/auction/ParcelMap.tsx` (`ParcelMapLegend` only)

- [ ] **Step 1: Read `frontend/AGENTS.md`**

Quick scan to confirm Next.js 16 caveat is fresh.

- [ ] **Step 2: Update the failing tests in `colors.test.ts`**

Replace the contents of `frontend/src/lib/parcelMap/colors.test.ts` with:

```ts
import { describe, it, expect } from "vitest";
import { gradientColor, slopeColor, dimOutside, MAP_COLORS } from "./colors";

describe("gradientColor (2-stop green->red)", () => {
  it("returns solid green when delta <= 0", () => {
    expect(gradientColor(-2)).toEqual(MAP_COLORS.green);
    expect(gradientColor(0)).toEqual(MAP_COLORS.green);
  });

  it("returns solid red when delta >= 8", () => {
    expect(gradientColor(8)).toEqual(MAP_COLORS.red);
    expect(gradientColor(12)).toEqual(MAP_COLORS.red);
  });

  it("lerps green->red linearly at the midpoint (delta = 4 m)", () => {
    const g = MAP_COLORS.green;
    const r = MAP_COLORS.red;
    const mid = gradientColor(4);
    expect(mid.r).toBeCloseTo((g.r + r.r) / 2, 0);
    expect(mid.g).toBeCloseTo((g.g + r.g) / 2, 0);
    expect(mid.b).toBeCloseTo((g.b + r.b) / 2, 0);
  });

  it("lerps green->red at delta = 2 m (25% of the way)", () => {
    const g = MAP_COLORS.green;
    const r = MAP_COLORS.red;
    const t = 0.25;
    const c = gradientColor(2);
    expect(c.r).toBeCloseTo(g.r + (r.r - g.r) * t, 0);
    expect(c.g).toBeCloseTo(g.g + (r.g - g.g) * t, 0);
    expect(c.b).toBeCloseTo(g.b + (r.b - g.b) * t, 0);
  });

  it("lerps green->red at delta = 6 m (75% of the way)", () => {
    const g = MAP_COLORS.green;
    const r = MAP_COLORS.red;
    const t = 0.75;
    const c = gradientColor(6);
    expect(c.r).toBeCloseTo(g.r + (r.r - g.r) * t, 0);
    expect(c.g).toBeCloseTo(g.g + (r.g - g.g) * t, 0);
    expect(c.b).toBeCloseTo(g.b + (r.b - g.b) * t, 0);
  });
});

describe("slopeColor (2-stop green->red, 0..45 deg)", () => {
  it("returns solid green at flat (0 rad)", () => {
    expect(slopeColor(0)).toEqual(MAP_COLORS.green);
  });

  it("returns solid red at 45 deg (Math.PI / 4 rad)", () => {
    expect(slopeColor(Math.PI / 4)).toEqual(MAP_COLORS.red);
  });

  it("saturates at red for slopes above 45 deg", () => {
    expect(slopeColor(Math.PI / 2)).toEqual(MAP_COLORS.red);
    expect(slopeColor(Math.PI)).toEqual(MAP_COLORS.red);
  });

  it("clamps to green for negative slopes (defensive)", () => {
    expect(slopeColor(-0.1)).toEqual(MAP_COLORS.green);
  });

  it("lerps green->red linearly at half scale (22.5 deg = Math.PI / 8)", () => {
    const g = MAP_COLORS.green;
    const r = MAP_COLORS.red;
    const c = slopeColor(Math.PI / 8);
    expect(c.r).toBeCloseTo((g.r + r.r) / 2, 0);
    expect(c.g).toBeCloseTo((g.g + r.g) / 2, 0);
    expect(c.b).toBeCloseTo((g.b + r.b) / 2, 0);
  });
});

describe("dimOutside", () => {
  it("lerps a given rgb 60% toward neutral gray (120, 120, 120)", () => {
    const dimmed = dimOutside({ r: 34, g: 197, b: 94 });
    expect(dimmed.r).toBe(Math.round(34 * 0.4 + 120 * 0.6));
    expect(dimmed.g).toBe(Math.round(197 * 0.4 + 120 * 0.6));
    expect(dimmed.b).toBe(Math.round(94 * 0.4 + 120 * 0.6));
  });

  it("returns gray for any gray input (no-op)", () => {
    const dimmed = dimOutside({ r: 120, g: 120, b: 120 });
    expect(dimmed).toEqual({ r: 120, g: 120, b: 120 });
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/lib/parcelMap/colors.test.ts
```

Expected: failures referencing the removed `MAP_COLORS.yellow` and the missing `slopeColor` export.

- [ ] **Step 4: Rewrite `colors.ts`**

Replace `frontend/src/lib/parcelMap/colors.ts` contents with:

```ts
/**
 * Pure gradient + dim math for the parcel map. No DOM, no React.
 *
 * Two color helpers feed the heatmap rendering:
 *
 *   gradientColor(deltaMeters)
 *     Elevation above the parcel's lowest cell. 2-stop lerp:
 *       delta <= 0   -> green
 *       delta >= 8m  -> red (un-flattenable spread)
 *       in between, smooth linear interpolation through olive/khaki tones.
 *
 *   slopeColor(slopeRad)
 *     Local terrain slope in radians. 2-stop lerp:
 *       0 rad           -> green (flat)
 *       Math.PI / 4 rad -> red (45 deg, practical SL unbuildable threshold)
 *       steeper still reads red.
 *
 * Outside-parcel cells in the 2D view are dimmed toward neutral gray (60%
 * toward gray) so the parcel pops visually.
 *
 * Colors are rgb triples (not hex) to keep the no-hex-colors verify guard
 * happy. The RGB values reference Tailwind's green-500 / red-500 / cyan-400
 * for visual consistency with the rest of the app.
 */
export interface Rgb {
  r: number;
  g: number;
  b: number;
}

export const MAP_COLORS = {
  green: { r: 34, g: 197, b: 94 }, // Tailwind green-500
  red: { r: 239, g: 68, b: 68 }, // Tailwind red-500
  cyan: { r: 34, g: 211, b: 238 }, // Tailwind cyan-400 (boundary outline)
  neutral: { r: 120, g: 120, b: 120 }, // dim target
} as const;

const SLOPE_RED_RAD = Math.PI / 4; // 45 deg saturation point
const ELEVATION_RED_M = 8; // 8m above parcel min saturates to red

/** Per-cell color from an elevation delta (cell elevation - parcel min). */
export function gradientColor(deltaMeters: number): Rgb {
  if (deltaMeters <= 0) return { ...MAP_COLORS.green };
  if (deltaMeters >= ELEVATION_RED_M) return { ...MAP_COLORS.red };
  const t = deltaMeters / ELEVATION_RED_M;
  return lerp(MAP_COLORS.green, MAP_COLORS.red, t);
}

/** Per-vertex color from a local slope angle in radians (0..pi/2). */
export function slopeColor(slopeRad: number): Rgb {
  if (slopeRad <= 0) return { ...MAP_COLORS.green };
  if (slopeRad >= SLOPE_RED_RAD) return { ...MAP_COLORS.red };
  const t = slopeRad / SLOPE_RED_RAD;
  return lerp(MAP_COLORS.green, MAP_COLORS.red, t);
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
```

Use Edit (the file exists). The change: remove `MAP_COLORS.yellow`; replace `gradientColor` body; add `slopeColor`; expand the file header comment.

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd frontend && npx vitest run src/lib/parcelMap/colors.test.ts
```

Expected: all `gradientColor`, `slopeColor`, and `dimOutside` cases pass.

- [ ] **Step 6: Update `ParcelMapLegend` in `ParcelMap.tsx`**

Edit the `ParcelMapLegend` function in `frontend/src/components/auction/ParcelMap.tsx` (currently lines 314-342). Replace the entire function body with the 2-stop version:

```tsx
function ParcelMapLegend() {
  // Linear gradient mirroring the 2-stop gradient used in colors.ts:
  //   green at delta=0 (parcel low point)
  //   red at delta=8+ m (un-flattenable spread)
  // RGB literals rather than #hex keep the no-hex-colors verify guard green.
  const stop = (c: { r: number; g: number; b: number }, pct: number) =>
    `rgb(${c.r}, ${c.g}, ${c.b}) ${pct}%`;
  const gradient = `linear-gradient(to right, ${stop(MAP_COLORS.green, 0)}, ${stop(MAP_COLORS.red, 100)})`;
  return (
    <div className="w-full max-w-[320px] flex flex-col gap-1">
      <div
        style={{ background: gradient }}
        className="h-2 w-full border border-border-subtle"
        aria-hidden="true"
      />
      <div className="flex justify-between text-[10px] text-fg-muted">
        <span>0 m</span>
        <span>+8 m+</span>
      </div>
      <p className="text-[10px] text-fg-muted">
        Color = elevation above the parcel&apos;s lowest cell.
        +4 m is the SL terraforming raise/lower limit; +8 m+ is
        un-flattenable spread.
      </p>
    </div>
  );
}
```

Use Edit. Changes: `gradient` string is two stops instead of three; the axis-labels row drops the middle `<span>+4 m</span>`. The explainer text is unchanged — it still mentions the SL terraforming threshold for context.

- [ ] **Step 7: Per-task verification**

```bash
cd frontend && npm run build && npm test && npm run verify
```

Expected: build green; full suite green (the `ParcelMap.test.tsx` doesn't assert on legend internals, so no test changes needed there); all four verify guards green.

If the `verify:no-hex-colors` guard flags anything, the `rgb(...)` calls inside the gradient string are already accepted by the guard (it targets `#xxxxxx`).

- [ ] **Step 8: Commit and push**

```bash
git add frontend/src/lib/parcelMap/colors.ts frontend/src/lib/parcelMap/colors.test.ts frontend/src/components/auction/ParcelMap.tsx
git commit -m "feat(frontend): 2-stop green/red gradient + legend update"
git push
```

---

## Task 2: useParcelMapColorMode hook

Mirrors `useParcelMapView` structure exactly: localStorage-backed, two-phase mount, lint suppression on the deliberate `setState-in-effect` pattern.

**Files:**
- Create: `frontend/src/hooks/useParcelMapColorMode.ts`
- Test: `frontend/src/hooks/useParcelMapColorMode.test.tsx`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/hooks/useParcelMapColorMode.test.tsx`:

```tsx
import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useParcelMapColorMode } from "./useParcelMapColorMode";

const STORAGE_KEY = "slpa:parcel-map:3d-color";

describe("useParcelMapColorMode", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("returns 'elevation' when localStorage is empty", () => {
    const { result } = renderHook(() => useParcelMapColorMode());
    expect(result.current[0]).toBe("elevation");
  });

  it("returns 'slope' when localStorage already holds 'slope'", () => {
    window.localStorage.setItem(STORAGE_KEY, "slope");
    const { result } = renderHook(() => useParcelMapColorMode());
    expect(result.current[0]).toBe("slope");
  });

  it("ignores junk values and falls back to 'elevation'", () => {
    window.localStorage.setItem(STORAGE_KEY, "asdf");
    const { result } = renderHook(() => useParcelMapColorMode());
    expect(result.current[0]).toBe("elevation");
  });

  it("setMode writes to localStorage and updates returned state", () => {
    const { result } = renderHook(() => useParcelMapColorMode());
    act(() => result.current[1]("slope"));
    expect(result.current[0]).toBe("slope");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("slope");
  });

  it("setMode back to 'elevation' updates both state and storage", () => {
    window.localStorage.setItem(STORAGE_KEY, "slope");
    const { result } = renderHook(() => useParcelMapColorMode());
    act(() => result.current[1]("elevation"));
    expect(result.current[0]).toBe("elevation");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("elevation");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/hooks/useParcelMapColorMode.test.tsx
```

Expected: all 5 tests fail with `Cannot find module './useParcelMapColorMode'`.

- [ ] **Step 3: Implement the hook**

Create `frontend/src/hooks/useParcelMapColorMode.ts`:

```ts
"use client";

import { useCallback, useEffect, useState } from "react";

export type ParcelMap3DColorMode = "elevation" | "slope";

const STORAGE_KEY = "slpa:parcel-map:3d-color";
const DEFAULT_MODE: ParcelMap3DColorMode = "elevation";

function readStoredMode(): ParcelMap3DColorMode {
  if (typeof window === "undefined") return DEFAULT_MODE;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw === "elevation" || raw === "slope") return raw;
    return DEFAULT_MODE;
  } catch {
    // localStorage can throw in privacy modes or quota-exceeded scenarios.
    return DEFAULT_MODE;
  }
}

/**
 * localStorage-backed color-mode choice for the parcel-map 3D view. Returns
 * the current mode and a setter that mirrors writes to localStorage.
 *
 * SSR-safe via a two-phase mount: the initial render returns DEFAULT_MODE on
 * BOTH the server pass and the client hydration pass, so the markup matches
 * and React does not warn about a hydration mismatch. After hydration the
 * useEffect runs, reads the stored value, and re-renders if it differs. A
 * naive useState lazy initializer would read localStorage during the client
 * hydration pass and produce different HTML than the server, causing a
 * mismatch warning whenever a returning visitor has "slope" stored. Mirrors
 * {@link useParcelMapView}.
 */
export function useParcelMapColorMode(): [
  ParcelMap3DColorMode,
  (next: ParcelMap3DColorMode) => void,
] {
  const [mode, setMode] = useState<ParcelMap3DColorMode>(DEFAULT_MODE);

  // Deliberate two-phase mount: see JSDoc above. Lint rules that flag
  // setState-in-effect do not model SSR hydration, so the suppression is
  // load-bearing here.
  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    setMode(readStoredMode());
  }, []);
  /* eslint-enable react-hooks/set-state-in-effect */

  const update = useCallback((next: ParcelMap3DColorMode) => {
    setMode(next);
    if (typeof window === "undefined") return;
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // localStorage write can throw under quota or privacy modes; swallow
      // so the in-session mode change still takes effect.
    }
  }, []);

  return [mode, update];
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend && npx vitest run src/hooks/useParcelMapColorMode.test.tsx
```

Expected: all 5 tests pass.

- [ ] **Step 5: Per-task verification**

```bash
cd frontend && npm run build && npm test && npm run verify
```

Expected: build green, full suite green, verify green.

- [ ] **Step 6: Commit and push**

```bash
git add frontend/src/hooks/useParcelMapColorMode.ts frontend/src/hooks/useParcelMapColorMode.test.tsx
git commit -m "feat(frontend): useParcelMapColorMode hook"
git push
```

---

## Task 3: computeSlopeGrid + buildHeightfieldGeometry walls/floor extension

The largest task. Adds the pure slope helper, extends the geometry builder's signature to include `floorY`, `mode`, and `slopeGrid`, and emits the wall/floor geometry inline.

**Files:**
- Modify: `frontend/src/lib/parcelMap3D/geometry.ts`
- Modify: `frontend/src/lib/parcelMap3D/geometry.test.ts`

- [ ] **Step 1: Update the failing geometry test**

Replace the contents of `frontend/src/lib/parcelMap3D/geometry.test.ts` with the version below. The changes from the current file:

- Add `computeSlopeGrid` imports and a new describe block.
- Update every `buildHeightfieldGeometry(upsampled, parcelMin)` call site to the new 5-arg form `(upsampled, parcelMin, floorY, "elevation", slopeGrid)`.
- Update the existing "produces UPSAMPLED_GRID^2 vertices ..." test to reflect the new total vertex / index counts (now including walls + floor).
- Add new describe blocks for wall geometry, wall colors, floor geometry, mode swap.

```ts
import { describe, it, expect } from "vitest";
import {
  UPSAMPLE_FACTOR,
  UPSAMPLED_GRID,
  bicubicUpsample,
  buildHeightfieldGeometry,
  buildPerimeterPoints,
  computeCameraDefaults,
  computeParcelStats,
  computeRegionBounds,
  computeSlopeGrid,
  decodeElevationGrid,
  isWebGLAvailable,
  sampleUpsampled,
} from "./geometry";

const TOP_VERTS = UPSAMPLED_GRID * UPSAMPLED_GRID;          // 16641
const TOP_INDICES = (UPSAMPLED_GRID - 1) * (UPSAMPLED_GRID - 1) * 6; // 98304
const WALL_VERTS = 4 * 2 * UPSAMPLED_GRID;                  // 1032
const WALL_INDICES = 4 * (UPSAMPLED_GRID - 1) * 6;          // 3072
const FLOOR_VERTS = 4;
const FLOOR_INDICES = 6;
const TOTAL_VERTS = TOP_VERTS + WALL_VERTS + FLOOR_VERTS;   // 17677
const TOTAL_INDICES = TOP_INDICES + WALL_INDICES + FLOOR_INDICES; // 101382

function layoutWith(setCells: Array<[number, number]>): Uint8Array {
  const bytes = new Uint8Array(512);
  for (const [row, col] of setCells) {
    const bitIndex = row * 64 + col;
    bytes[bitIndex >> 3] |= 1 << (7 - (bitIndex & 7));
  }
  return bytes;
}

function heightsAll(value: number): Uint8Array {
  const b = new Uint8Array(4096);
  b.fill(value);
  return b;
}

function flatScene() {
  const upsampled = bicubicUpsample(decodeElevationGrid(heightsAll(0), 22, 0.5));
  const slope = computeSlopeGrid(upsampled);
  return { upsampled, slope, parcelMin: 22, floorY: 14 };
}

describe("decodeElevationGrid", () => {
  it("decodes a flat heightmap to all baseMeters", () => {
    const grid = decodeElevationGrid(heightsAll(0), 22, 0.5);
    expect(grid.length).toBe(64 * 64);
    expect(grid[0]).toBeCloseTo(22.0);
    expect(grid[4095]).toBeCloseTo(22.0);
  });

  it("decodes a varying heightmap with stepMeters scaling", () => {
    const h = new Uint8Array(4096);
    h[10] = 100;
    const grid = decodeElevationGrid(h, 22, 0.5);
    expect(grid[10]).toBeCloseTo(22.0 + 100 * 0.5);
  });
});

describe("bicubicUpsample", () => {
  it("returns a flat grid for a flat input (interpolation does not invent variation)", () => {
    const input = new Float32Array(64 * 64).fill(25);
    const out = bicubicUpsample(input);
    expect(out.length).toBe(UPSAMPLED_GRID * UPSAMPLED_GRID);
    for (const y of out) expect(y).toBeCloseTo(25, 4);
  });

  it("preserves sample values at upsampled vertices that coincide with original samples", () => {
    const input = new Float32Array(64 * 64);
    input[10 * 64 + 20] = 100;
    const out = bicubicUpsample(input);
    expect(out[20 * UPSAMPLED_GRID + 40]).toBeCloseTo(100, 4);
  });

  it("smoothly interpolates between two adjacent samples", () => {
    const input = new Float32Array(64 * 64);
    input[10 * 64 + 5] = 0;
    input[10 * 64 + 6] = 10;
    const out = bicubicUpsample(input);
    const mid = out[20 * UPSAMPLED_GRID + 11];
    expect(mid).toBeGreaterThan(2);
    expect(mid).toBeLessThan(8);
  });
});

describe("computeRegionBounds", () => {
  it("returns base..base when all cells are zero", () => {
    const b = computeRegionBounds(decodeElevationGrid(heightsAll(0), 22, 0.5));
    expect(b).toEqual({ rMin: 22.0, rMax: 22.0 });
  });

  it("returns base..base+255*step when cells span the full uint8 range", () => {
    const h = new Uint8Array(4096);
    h[0] = 0;
    h[1] = 255;
    const b = computeRegionBounds(decodeElevationGrid(h, 22, 0.5));
    expect(b.rMin).toBeCloseTo(22.0);
    expect(b.rMax).toBeCloseTo(22.0 + 255 * 0.5);
  });
});

describe("computeParcelStats", () => {
  it("returns null when the parcel has no cells", () => {
    const stats = computeParcelStats(
      new Uint8Array(512), decodeElevationGrid(heightsAll(0), 22, 0.5),
    );
    expect(stats).toBeNull();
  });

  it("min/max/count over only the parcel cells", () => {
    const h = new Uint8Array(4096);
    h[10 * 64 + 10] = 0;
    h[10 * 64 + 11] = 10;
    h[20 * 64 + 20] = 100;
    const layout = layoutWith([[10, 10], [10, 11]]);
    const stats = computeParcelStats(layout, decodeElevationGrid(h, 22, 0.5))!;
    expect(stats.parcelCellCount).toBe(2);
    expect(stats.parcelMin).toBeCloseTo(22.0);
    expect(stats.parcelMax).toBeCloseTo(27.0);
  });
});

describe("computeSlopeGrid", () => {
  it("returns a Float32Array of length UPSAMPLED_GRID^2", () => {
    const upsampled = bicubicUpsample(decodeElevationGrid(heightsAll(0), 22, 0.5));
    const slope = computeSlopeGrid(upsampled);
    expect(slope).toBeInstanceOf(Float32Array);
    expect(slope.length).toBe(UPSAMPLED_GRID * UPSAMPLED_GRID);
  });

  it("returns all zeros for a flat input", () => {
    const upsampled = new Float32Array(UPSAMPLED_GRID * UPSAMPLED_GRID).fill(25);
    const slope = computeSlopeGrid(upsampled);
    for (const s of slope) expect(s).toBeCloseTo(0, 6);
  });

  it("returns a uniform non-zero slope for a constant ramp in X", () => {
    // h(x, z) = x / 4 -> dh/dx = 0.25, dh/dz = 0 -> slope = atan(0.25)
    const upsampled = new Float32Array(UPSAMPLED_GRID * UPSAMPLED_GRID);
    for (let uRow = 0; uRow < UPSAMPLED_GRID; uRow++) {
      for (let uCol = 0; uCol < UPSAMPLED_GRID; uCol++) {
        upsampled[uRow * UPSAMPLED_GRID + uCol] = (uCol * 2) / 4;
      }
    }
    const slope = computeSlopeGrid(upsampled);
    // Inspect an interior vertex (uRow=64, uCol=64) -- avoids the one-sided
    // edge fallback.
    const interior = slope[64 * UPSAMPLED_GRID + 64];
    expect(interior).toBeCloseTo(Math.atan(0.25), 4);
  });

  it("uses clamp-to-edge one-sided differences at the boundary (no NaN)", () => {
    const upsampled = new Float32Array(UPSAMPLED_GRID * UPSAMPLED_GRID);
    for (let i = 0; i < upsampled.length; i++) upsampled[i] = i % 7;
    const slope = computeSlopeGrid(upsampled);
    for (const s of slope) {
      expect(Number.isFinite(s)).toBe(true);
      expect(s).toBeGreaterThanOrEqual(0);
    }
  });
});

describe("sampleUpsampled", () => {
  it("samples upsampled grid at world (x, z) coordinates with bilinear fallback", () => {
    const upsampled = new Float32Array(UPSAMPLED_GRID * UPSAMPLED_GRID).fill(30);
    expect(sampleUpsampled(upsampled, 40, 40)).toBeCloseTo(30);
  });
});

describe("buildPerimeterPoints", () => {
  it("returns 8 endpoints (4 edges * 2 endpoints) for a single isolated parcel cell", () => {
    const upsampled = bicubicUpsample(decodeElevationGrid(heightsAll(0), 22, 0.5));
    const points = buildPerimeterPoints(layoutWith([[10, 10]]), upsampled);
    expect(points.length).toBe(8);
    expect(points[0]).toHaveLength(3);
  });

  it("interior parcel cell with all four neighbors in-parcel emits zero edges", () => {
    const layout = layoutWith([
      [10, 10],
      [9, 10], [11, 10], [10, 9], [10, 11],
    ]);
    const upsampled = bicubicUpsample(decodeElevationGrid(heightsAll(0), 22, 0.5));
    const allPoints = buildPerimeterPoints(layout, upsampled);
    expect(allPoints.length).toBe(24);
  });

  it("returns an empty array when the parcel has zero cells", () => {
    const upsampled = bicubicUpsample(decodeElevationGrid(heightsAll(0), 22, 0.5));
    expect(buildPerimeterPoints(new Uint8Array(512), upsampled)).toEqual([]);
  });
});

describe("computeCameraDefaults", () => {
  it("targets the geometric center at the mid elevation", () => {
    const cam = computeCameraDefaults({ rMin: 20, rMax: 40 });
    expect(cam.target).toEqual([128, 30, 128]);
    expect(cam.fovDeg).toBe(50);
  });

  it("camera sits at 45 deg azimuth, 30 deg elevation above the target", () => {
    const cam = computeCameraDefaults({ rMin: 0, rMax: 0 });
    const [cx, cy, cz] = cam.position;
    expect(cy).toBeGreaterThan(0);
    expect(cx - 128).toBeCloseTo(cz - 128);
    expect(cx - 128).toBeGreaterThan(0);
  });
});

describe("UPSAMPLE_FACTOR", () => {
  it("is exactly 2, so cell-edge coords align with upsampled vertices", () => {
    expect(UPSAMPLE_FACTOR).toBe(2);
    expect(UPSAMPLED_GRID).toBe(64 * 2 + 1);
  });
});

describe("isWebGLAvailable", () => {
  it("returns a boolean without throwing", () => {
    expect(typeof isWebGLAvailable()).toBe("boolean");
  });
});

describe("buildHeightfieldGeometry (top + walls + floor)", () => {
  it("produces TOTAL_VERTS vertices and TOTAL_INDICES indices", () => {
    const { upsampled, slope, parcelMin, floorY } = flatScene();
    const geom = buildHeightfieldGeometry(
      upsampled, parcelMin, floorY, "elevation", slope,
    );
    expect(geom.getAttribute("position").count).toBe(TOTAL_VERTS);
    expect(geom.getAttribute("color").count).toBe(TOTAL_VERTS);
    expect(geom.index!.count).toBe(TOTAL_INDICES);
  });

  it("vertex 0 (SW corner of top mesh) sits at world (0, baseMeters, 0)", () => {
    const { upsampled, slope, parcelMin, floorY } = flatScene();
    const geom = buildHeightfieldGeometry(
      upsampled, parcelMin, floorY, "elevation", slope,
    );
    const pos = geom.getAttribute("position");
    expect(pos.getX(0)).toBe(0);
    expect(pos.getY(0)).toBeCloseTo(22.0);
    expect(pos.getZ(0)).toBe(0);
  });

  it("colors top vertex 0 with green-500 when elevation equals parcelMin", () => {
    const { upsampled, slope, parcelMin, floorY } = flatScene();
    const geom = buildHeightfieldGeometry(
      upsampled, parcelMin, floorY, "elevation", slope,
    );
    const colors = geom.getAttribute("color");
    expect(colors.getX(0)).toBeCloseTo(34 / 255);
    expect(colors.getY(0)).toBeCloseTo(197 / 255);
    expect(colors.getZ(0)).toBeCloseTo(94 / 255);
  });

  it("top mesh produces upward-facing vertex normals (avg Y > 0.3)", () => {
    const heights = new Uint8Array(4096);
    for (let i = 0; i < heights.length; i++) heights[i] = i % 50;
    const raw = decodeElevationGrid(heights, 22, 0.5);
    const upsampled = bicubicUpsample(raw);
    const slope = computeSlopeGrid(upsampled);
    const stats = computeParcelStats(layoutWith([[10, 10]]), raw)!;
    const bounds = computeRegionBounds(upsampled);
    const geom = buildHeightfieldGeometry(
      upsampled, stats.parcelMin, bounds.rMin - 8, "elevation", slope,
    );
    const normals = geom.getAttribute("normal");
    // Average over the TOP mesh vertices only (first TOP_VERTS entries).
    let sumY = 0;
    for (let i = 0; i < TOP_VERTS; i++) sumY += normals.getY(i);
    expect(sumY / TOP_VERTS).toBeGreaterThan(0.3);
  });

  it("each wall's face normal points outward from region center", () => {
    const { upsampled, slope, parcelMin, floorY } = flatScene();
    const geom = buildHeightfieldGeometry(
      upsampled, parcelMin, floorY, "elevation", slope,
    );
    const normals = geom.getAttribute("normal");
    // Walls live at vertex indices [TOP_VERTS, TOP_VERTS + WALL_VERTS).
    // Wall vertex order: south (-Z), north (+Z), west (-X), east (+X), each
    // contributing 2 * UPSAMPLED_GRID vertices.
    const PER_WALL = 2 * UPSAMPLED_GRID;
    function avgNormal(startVtx: number) {
      let x = 0, y = 0, z = 0;
      for (let i = 0; i < PER_WALL; i++) {
        x += normals.getX(startVtx + i);
        y += normals.getY(startVtx + i);
        z += normals.getZ(startVtx + i);
      }
      return [x / PER_WALL, y / PER_WALL, z / PER_WALL];
    }
    const southAvg = avgNormal(TOP_VERTS + 0 * PER_WALL);
    const northAvg = avgNormal(TOP_VERTS + 1 * PER_WALL);
    const westAvg = avgNormal(TOP_VERTS + 2 * PER_WALL);
    const eastAvg = avgNormal(TOP_VERTS + 3 * PER_WALL);
    expect(southAvg[2]).toBeLessThan(-0.5);  // points -Z
    expect(northAvg[2]).toBeGreaterThan(0.5); // points +Z
    expect(westAvg[0]).toBeLessThan(-0.5);   // points -X
    expect(eastAvg[0]).toBeGreaterThan(0.5);  // points +X
  });

  it("floor face normal points -Y (downward)", () => {
    const { upsampled, slope, parcelMin, floorY } = flatScene();
    const geom = buildHeightfieldGeometry(
      upsampled, parcelMin, floorY, "elevation", slope,
    );
    const normals = geom.getAttribute("normal");
    const floorStart = TOP_VERTS + WALL_VERTS;
    let sumY = 0;
    for (let i = 0; i < FLOOR_VERTS; i++) sumY += normals.getY(floorStart + i);
    expect(sumY / FLOOR_VERTS).toBeCloseTo(-1, 1);
  });

  it("wall vertex color = top vertex color at same X/Z, multiplied by 0.55 (top + bottom edge match)", () => {
    const { upsampled, slope, parcelMin, floorY } = flatScene();
    const geom = buildHeightfieldGeometry(
      upsampled, parcelMin, floorY, "elevation", slope,
    );
    const colors = geom.getAttribute("color");
    // Top mesh vertex (0, 0) sits at color = green / 255 (since flat input).
    const topR = colors.getX(0);
    const topG = colors.getY(0);
    const topB = colors.getZ(0);
    // South wall starts immediately after the top mesh. The first 2 wall
    // vertices are the top-edge and bottom-edge at (uCol=0, uRow=0).
    const southStart = TOP_VERTS;
    const wallTopR = colors.getX(southStart);
    const wallBottomR = colors.getX(southStart + 1);
    expect(wallTopR).toBeCloseTo(topR * 0.55, 4);
    expect(wallBottomR).toBeCloseTo(topR * 0.55, 4);
    expect(colors.getY(southStart)).toBeCloseTo(topG * 0.55, 4);
    expect(colors.getZ(southStart)).toBeCloseTo(topB * 0.55, 4);
  });

  it("floor vertex color is the fixed earth tone rgb(60, 50, 40)", () => {
    const { upsampled, slope, parcelMin, floorY } = flatScene();
    const geom = buildHeightfieldGeometry(
      upsampled, parcelMin, floorY, "elevation", slope,
    );
    const colors = geom.getAttribute("color");
    const floorStart = TOP_VERTS + WALL_VERTS;
    expect(colors.getX(floorStart)).toBeCloseTo(60 / 255, 4);
    expect(colors.getY(floorStart)).toBeCloseTo(50 / 255, 4);
    expect(colors.getZ(floorStart)).toBeCloseTo(40 / 255, 4);
  });

  it("mode swap (elevation -> slope) produces different top colors on a sloped input", () => {
    // Ramp input: cells climb in X. In elevation mode the SW corner is
    // parcelMin -> green. In slope mode the SW corner sits on a non-zero
    // slope -> green-shifted-toward-red color (not pure green).
    const h = new Uint8Array(4096);
    for (let row = 0; row < 64; row++) {
      for (let col = 0; col < 64; col++) {
        h[row * 64 + col] = col * 2;
      }
    }
    const raw = decodeElevationGrid(h, 22, 0.5);
    const upsampled = bicubicUpsample(raw);
    const slope = computeSlopeGrid(upsampled);
    const stats = computeParcelStats(layoutWith([[0, 0]]), raw)!;
    const bounds = computeRegionBounds(upsampled);
    const floorY = bounds.rMin - 8;
    const elevGeom = buildHeightfieldGeometry(
      upsampled, stats.parcelMin, floorY, "elevation", slope,
    );
    const slopeGeom = buildHeightfieldGeometry(
      upsampled, stats.parcelMin, floorY, "slope", slope,
    );
    const elevColor0 = elevGeom.getAttribute("color");
    const slopeColor0 = slopeGeom.getAttribute("color");
    // The SW corner top vertex should differ between the two modes.
    const elevR = elevColor0.getX(0);
    const slopeR = slopeColor0.getX(0);
    expect(slopeR).not.toBeCloseTo(elevR, 2);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/lib/parcelMap3D/geometry.test.ts
```

Expected: the existing tests fail because `computeSlopeGrid` doesn't exist; the new wall/floor tests fail because the new signature isn't in place.

- [ ] **Step 3: Add `computeSlopeGrid` to `geometry.ts`**

Insert this helper into `frontend/src/lib/parcelMap3D/geometry.ts` after `bicubicUpsample` and before `computeRegionBounds`. Use Edit.

```ts
/**
 * Closed-form finite-difference slope at every upsampled vertex. Returns a
 * {@link UPSAMPLED_GRID} squared Float32Array of slope angles in radians
 * (0 = flat, pi/2 = vertical). Edges use one-sided differences (clamp-to-edge);
 * interior vertices use centered differences.
 *
 *   dhdx = (h[uRow,   uCol+1] - h[uRow,   uCol-1]) / (2 * UPSAMPLED_SPACING_M)
 *   dhdz = (h[uRow+1, uCol  ] - h[uRow-1, uCol  ]) / (2 * UPSAMPLED_SPACING_M)
 *   slope = atan(sqrt(dhdx*dhdx + dhdz*dhdz))
 *
 * Pure math; safe to memoize via {@code useMemo}.
 */
export function computeSlopeGrid(upsampled: Float32Array): Float32Array {
  const out = new Float32Array(UPSAMPLED_GRID * UPSAMPLED_GRID);
  const inv2 = 1 / (2 * UPSAMPLED_SPACING_M);
  const invEdge = 1 / UPSAMPLED_SPACING_M;
  for (let uRow = 0; uRow < UPSAMPLED_GRID; uRow++) {
    const rowNorth = Math.min(UPSAMPLED_GRID - 1, uRow + 1);
    const rowSouth = Math.max(0, uRow - 1);
    const rowSpanIsCentered = rowNorth - rowSouth === 2;
    for (let uCol = 0; uCol < UPSAMPLED_GRID; uCol++) {
      const colEast = Math.min(UPSAMPLED_GRID - 1, uCol + 1);
      const colWest = Math.max(0, uCol - 1);
      const colSpanIsCentered = colEast - colWest === 2;
      const dhdx =
        (upsampled[uRow * UPSAMPLED_GRID + colEast] -
          upsampled[uRow * UPSAMPLED_GRID + colWest]) *
        (colSpanIsCentered ? inv2 : invEdge);
      const dhdz =
        (upsampled[rowNorth * UPSAMPLED_GRID + uCol] -
          upsampled[rowSouth * UPSAMPLED_GRID + uCol]) *
        (rowSpanIsCentered ? inv2 : invEdge);
      out[uRow * UPSAMPLED_GRID + uCol] = Math.atan(Math.sqrt(dhdx * dhdx + dhdz * dhdz));
    }
  }
  return out;
}
```

- [ ] **Step 4: Rewrite `buildHeightfieldGeometry` to the new signature and emit walls + floor**

Replace the existing `buildHeightfieldGeometry` function in `geometry.ts` (currently lines 175-224) with the version below. Also import `slopeColor` from `colors.ts` at the top of the file. Use Edit.

First, update the colors import at the top:

```ts
import { gradientColor, slopeColor } from "@/lib/parcelMap/colors";
```

Then replace the function:

```ts
/**
 * Build the heightfield mesh as a single indexed {@link BufferGeometry}.
 * Includes the top surface (129x129 vertices, smooth heightfield), four
 * extruded perimeter walls reaching down to {@code floorY}, and a bottom
 * floor quad at {@code floorY}. Vertex colors come from one of two helpers
 * depending on {@code mode}: elevation (delta from parcelMin) or slope (rad).
 * Walls reuse the perimeter top color multiplied by {@link WALL_DIM}; both
 * top and bottom edges of each wall share the same color. Floor uses a
 * fixed neutral earth tone.
 *
 * Vertex layout (used by tests asserting positions/colors/normals by index):
 *   [0 .. TOP_VERTS)              top mesh, row-major (uRow * UPSAMPLED_GRID + uCol)
 *   [TOP_VERTS .. +PER_WALL)      south wall, pairs of (top-edge, bottom-edge) by uCol
 *   [+PER_WALL .. +2*PER_WALL)    north wall, same pair ordering by uCol
 *   [+2*PER_WALL .. +3*PER_WALL)  west wall, pairs by uRow
 *   [+3*PER_WALL .. +4*PER_WALL)  east wall, pairs by uRow
 *   [TOP_VERTS + WALL_VERTS ..)   floor (4 vertices, SW SE NW NE)
 *
 * Wall triangle winding is CCW from outside the region so face normals
 * point outward (south=-Z, north=+Z, west=-X, east=+X). Floor winding is
 * CCW from below so face normal points -Y.
 */
export function buildHeightfieldGeometry(
  upsampled: Float32Array,
  parcelMin: number,
  floorY: number,
  mode: "elevation" | "slope",
  slopeGrid: Float32Array,
): BufferGeometry {
  const TOP_VERTS = UPSAMPLED_GRID * UPSAMPLED_GRID;
  const QUAD_COUNT = (UPSAMPLED_GRID - 1) * (UPSAMPLED_GRID - 1);
  const TOP_INDICES = QUAD_COUNT * 6;
  const PER_WALL_VERTS = 2 * UPSAMPLED_GRID;
  const PER_WALL_INDICES = (UPSAMPLED_GRID - 1) * 6;
  const WALL_VERTS = 4 * PER_WALL_VERTS;
  const WALL_INDICES = 4 * PER_WALL_INDICES;
  const FLOOR_VERTS = 4;
  const FLOOR_INDICES = 6;

  const totalVerts = TOP_VERTS + WALL_VERTS + FLOOR_VERTS;
  const totalIndices = TOP_INDICES + WALL_INDICES + FLOOR_INDICES;

  const positions = new Float32Array(totalVerts * 3);
  const colors = new Float32Array(totalVerts * 3);
  const indices = new Uint32Array(totalIndices);

  // ---- 1. Top surface ----
  for (let uRow = 0; uRow < UPSAMPLED_GRID; uRow++) {
    for (let uCol = 0; uCol < UPSAMPLED_GRID; uCol++) {
      const vIdx = uRow * UPSAMPLED_GRID + uCol;
      const y = upsampled[vIdx];
      const x = uCol * UPSAMPLED_SPACING_M;
      const z = uRow * UPSAMPLED_SPACING_M;
      positions[vIdx * 3 + 0] = x;
      positions[vIdx * 3 + 1] = y;
      positions[vIdx * 3 + 2] = z;
      const c = topColorAt(uRow, uCol, upsampled, slopeGrid, parcelMin, mode);
      colors[vIdx * 3 + 0] = c.r / 255;
      colors[vIdx * 3 + 1] = c.g / 255;
      colors[vIdx * 3 + 2] = c.b / 255;
    }
  }
  let iIdx = 0;
  for (let uRow = 0; uRow < UPSAMPLED_GRID - 1; uRow++) {
    for (let uCol = 0; uCol < UPSAMPLED_GRID - 1; uCol++) {
      const sw = uRow * UPSAMPLED_GRID + uCol;
      const se = sw + 1;
      const nw = sw + UPSAMPLED_GRID;
      const ne = nw + 1;
      indices[iIdx++] = sw; indices[iIdx++] = ne; indices[iIdx++] = se;
      indices[iIdx++] = sw; indices[iIdx++] = nw; indices[iIdx++] = ne;
    }
  }

  // ---- 2. Walls (south, north, west, east in this order) ----
  let vCursor = TOP_VERTS;

  // South wall (z=0, faces -Z). Pairs ordered by uCol: top vertex, then bottom.
  vCursor = emitWall({
    perimeterIndex: (uCol) => 0 * UPSAMPLED_GRID + uCol,
    perimeterX: (uCol) => uCol * UPSAMPLED_SPACING_M,
    perimeterZ: () => 0,
    perimeterCount: UPSAMPLED_GRID,
    // Winding for outward -Z: see test "each wall's face normal points outward".
    quadIndices: (baseTopA, baseBottomA, baseTopB, baseBottomB) => [
      baseTopA, baseBottomA, baseBottomB,
      baseTopA, baseBottomB, baseTopB,
    ],
    isVertical: false,
  });
  // North wall (z=256, faces +Z).
  vCursor = emitWall({
    perimeterIndex: (uCol) => (UPSAMPLED_GRID - 1) * UPSAMPLED_GRID + uCol,
    perimeterX: (uCol) => uCol * UPSAMPLED_SPACING_M,
    perimeterZ: () => (UPSAMPLED_GRID - 1) * UPSAMPLED_SPACING_M,
    perimeterCount: UPSAMPLED_GRID,
    // Reverse winding so normal flips to +Z.
    quadIndices: (baseTopA, baseBottomA, baseTopB, baseBottomB) => [
      baseTopA, baseTopB, baseBottomB,
      baseTopA, baseBottomB, baseBottomA,
    ],
    isVertical: false,
  });
  // West wall (x=0, faces -X). Iterate by uRow.
  vCursor = emitWall({
    perimeterIndex: (uRow) => uRow * UPSAMPLED_GRID + 0,
    perimeterX: () => 0,
    perimeterZ: (uRow) => uRow * UPSAMPLED_SPACING_M,
    perimeterCount: UPSAMPLED_GRID,
    // Outward -X.
    quadIndices: (baseTopA, baseBottomA, baseTopB, baseBottomB) => [
      baseTopA, baseTopB, baseBottomB,
      baseTopA, baseBottomB, baseBottomA,
    ],
    isVertical: true,
  });
  // East wall (x=256, faces +X).
  vCursor = emitWall({
    perimeterIndex: (uRow) => uRow * UPSAMPLED_GRID + (UPSAMPLED_GRID - 1),
    perimeterX: () => (UPSAMPLED_GRID - 1) * UPSAMPLED_SPACING_M,
    perimeterZ: (uRow) => uRow * UPSAMPLED_SPACING_M,
    perimeterCount: UPSAMPLED_GRID,
    // Reverse winding so normal flips to +X.
    quadIndices: (baseTopA, baseBottomA, baseTopB, baseBottomB) => [
      baseTopA, baseBottomA, baseBottomB,
      baseTopA, baseBottomB, baseTopB,
    ],
    isVertical: true,
  });

  function emitWall(opts: {
    perimeterIndex: (i: number) => number;
    perimeterX: (i: number) => number;
    perimeterZ: (i: number) => number;
    perimeterCount: number;
    quadIndices: (baseTopA: number, baseBottomA: number, baseTopB: number, baseBottomB: number) => number[];
    isVertical: boolean;
  }): number {
    const walVertStart = vCursor;
    for (let i = 0; i < opts.perimeterCount; i++) {
      const topIdx = opts.perimeterIndex(i);
      const x = opts.perimeterX(i);
      const z = opts.perimeterZ(i);
      const topY = upsampled[topIdx];

      // Top-edge wall vertex
      positions[vCursor * 3 + 0] = x;
      positions[vCursor * 3 + 1] = topY;
      positions[vCursor * 3 + 2] = z;
      // Bottom-edge wall vertex (same X/Z, at floorY)
      positions[(vCursor + 1) * 3 + 0] = x;
      positions[(vCursor + 1) * 3 + 1] = floorY;
      positions[(vCursor + 1) * 3 + 2] = z;

      // Color: top mesh color at this perimeter vertex, multiplied by WALL_DIM.
      // Same color for both top and bottom edge of the wall.
      const topR = colors[topIdx * 3 + 0];
      const topG = colors[topIdx * 3 + 1];
      const topB = colors[topIdx * 3 + 2];
      colors[vCursor * 3 + 0] = topR * WALL_DIM;
      colors[vCursor * 3 + 1] = topG * WALL_DIM;
      colors[vCursor * 3 + 2] = topB * WALL_DIM;
      colors[(vCursor + 1) * 3 + 0] = topR * WALL_DIM;
      colors[(vCursor + 1) * 3 + 1] = topG * WALL_DIM;
      colors[(vCursor + 1) * 3 + 2] = topB * WALL_DIM;

      vCursor += 2;
    }
    for (let i = 0; i < opts.perimeterCount - 1; i++) {
      const baseTopA = walVertStart + i * 2;
      const baseBottomA = baseTopA + 1;
      const baseTopB = walVertStart + (i + 1) * 2;
      const baseBottomB = baseTopB + 1;
      const tri = opts.quadIndices(baseTopA, baseBottomA, baseTopB, baseBottomB);
      for (let t = 0; t < tri.length; t++) indices[iIdx++] = tri[t];
    }
    return vCursor;
  }

  // ---- 3. Floor ----
  const floorStart = TOP_VERTS + WALL_VERTS;
  const REGION_END = (UPSAMPLED_GRID - 1) * UPSAMPLED_SPACING_M; // 256
  const floorCorners: Array<[number, number, number]> = [
    [0, floorY, 0],                     // sw
    [REGION_END, floorY, 0],            // se
    [0, floorY, REGION_END],            // nw
    [REGION_END, floorY, REGION_END],   // ne
  ];
  for (let i = 0; i < 4; i++) {
    const [fx, fy, fz] = floorCorners[i];
    positions[(floorStart + i) * 3 + 0] = fx;
    positions[(floorStart + i) * 3 + 1] = fy;
    positions[(floorStart + i) * 3 + 2] = fz;
    colors[(floorStart + i) * 3 + 0] = FLOOR_COLOR.r / 255;
    colors[(floorStart + i) * 3 + 1] = FLOOR_COLOR.g / 255;
    colors[(floorStart + i) * 3 + 2] = FLOOR_COLOR.b / 255;
  }
  // Two CCW triangles seen from -Y (below) so face normal points -Y.
  // SW(0), SE(1), NW(2), NE(3). Looking from -Y: (sw, nw, ne) + (sw, ne, se).
  indices[iIdx++] = floorStart + 0; indices[iIdx++] = floorStart + 2; indices[iIdx++] = floorStart + 3;
  indices[iIdx++] = floorStart + 0; indices[iIdx++] = floorStart + 3; indices[iIdx++] = floorStart + 1;

  const geometry = new BufferGeometry();
  geometry.setAttribute("position", new Float32BufferAttribute(positions, 3));
  geometry.setAttribute("color", new Float32BufferAttribute(colors, 3));
  geometry.setIndex(new BufferAttribute(indices, 1));
  geometry.computeVertexNormals();
  return geometry;
}

/** Wall vertex color = top color * WALL_DIM (darker shaded side). */
const WALL_DIM = 0.55;
/** Floor face color (defensive coverage for low-orbit visibility). */
const FLOOR_COLOR = { r: 60, g: 50, b: 40 };

function topColorAt(
  uRow: number,
  uCol: number,
  upsampled: Float32Array,
  slopeGrid: Float32Array,
  parcelMin: number,
  mode: "elevation" | "slope",
) {
  const idx = uRow * UPSAMPLED_GRID + uCol;
  if (mode === "elevation") {
    return gradientColor(upsampled[idx] - parcelMin);
  }
  return slopeColor(slopeGrid[idx]);
}
```

Use Edit. The function body grows substantially (from ~50 lines to ~150). The signature changes are: new params `floorY`, `mode`, `slopeGrid`; the body now emits walls and a floor in addition to the top mesh. The two new module-level constants (`WALL_DIM`, `FLOOR_COLOR`) and the inline helper `topColorAt` come below the function.

- [ ] **Step 5: Run all geometry tests to verify they pass**

```bash
cd frontend && npx vitest run src/lib/parcelMap3D/geometry.test.ts
```

Expected: all tests pass. The total count should be ~31 (existing ~22 plus new computeSlopeGrid + new wall/floor + new mode-swap cases).

If a wall-normal test fails, the winding for that wall is reversed — toggle the order of the two triangles in `quadIndices` for the affected wall (or swap pairs within a triangle). The test pinpoints which wall (`southAvg`, `northAvg`, `westAvg`, `eastAvg`) is wrong.

- [ ] **Step 6: Per-task verification**

```bash
cd frontend && npm run build && npm test && npm run verify
```

Expected: build green (the new signature compiles, callers in `ParcelMap3D.tsx` will break — that's fixed in Task 5; for now, this task's verification skips Task 5's file by running the smaller per-file test first then the full suite which WILL surface a type error if anything else is wrong).

If `npm test` reports `ParcelMap3D.test.tsx` failing because the 2-arg call to `buildHeightfieldGeometry` is now invalid, accept that failure — Task 5 fixes it. Document it in the per-task report. Do NOT update `ParcelMap3D.tsx` here.

Actually: this DOES affect the build (tsc will fail on the 2-arg call site in `ParcelMap3D.tsx`). To keep this task atomically passing on its own, **temporarily update `ParcelMap3D.tsx`** to pass placeholder args:

```tsx
// Temporary, replaced cleanly in Task 5:
return buildHeightfieldGeometry(
  upsampledGrid, stats.parcelMin, bounds.rMin - 8, "elevation",
  // Placeholder slope grid - Task 5 wires the real computeSlopeGrid memo.
  new Float32Array(UPSAMPLED_GRID * UPSAMPLED_GRID),
);
```

Where `UPSAMPLED_GRID` is imported alongside the existing imports. This keeps build green; Task 5 swaps the placeholder for the real `slopeGrid` memo + adds the color-mode toggle wiring.

Use Edit on `ParcelMap3D.tsx` for this transitional change. Document it in the commit.

- [ ] **Step 7: Commit and push**

```bash
git add frontend/src/lib/parcelMap3D/geometry.ts frontend/src/lib/parcelMap3D/geometry.test.ts frontend/src/components/auction/ParcelMap3D.tsx
git commit -m "feat(frontend): heightfield walls + floor + slope grid helper"
git push
```

The commit body should note: "ParcelMap3D temporarily passes elevation mode + placeholder slope grid; full wiring follows in the toggle task."

---

## Task 4: ParcelMap3DColorModeToggle component

Pure presentational ARIA radio group. No localStorage, no state — the owning component wires `mode` and `onChange` from the hook.

**Files:**
- Create: `frontend/src/components/auction/ParcelMap3DColorModeToggle.tsx`
- Test: `frontend/src/components/auction/ParcelMap3DColorModeToggle.test.tsx`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/components/auction/ParcelMap3DColorModeToggle.test.tsx`:

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { ParcelMap3DColorModeToggle } from "./ParcelMap3DColorModeToggle";

describe("ParcelMap3DColorModeToggle", () => {
  it("renders an ARIA radio group with the correct aria-label", () => {
    render(<ParcelMap3DColorModeToggle mode="elevation" onChange={() => {}} />);
    const group = screen.getByRole("radiogroup", { name: "Color by" });
    expect(group).toBeInTheDocument();
  });

  it("renders two radio buttons labelled Elevation and Slope", () => {
    render(<ParcelMap3DColorModeToggle mode="elevation" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Slope" })).toBeInTheDocument();
  });

  it("aria-checked reflects the current mode prop", () => {
    const { rerender } = render(
      <ParcelMap3DColorModeToggle mode="elevation" onChange={() => {}} />,
    );
    expect(screen.getByRole("radio", { name: "Elevation" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "Slope" })).toHaveAttribute("aria-checked", "false");
    rerender(<ParcelMap3DColorModeToggle mode="slope" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" })).toHaveAttribute("aria-checked", "false");
    expect(screen.getByRole("radio", { name: "Slope" })).toHaveAttribute("aria-checked", "true");
  });

  it("clicking a radio fires onChange with the corresponding mode value", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap3DColorModeToggle mode="elevation" onChange={onChange} />);
    await user.click(screen.getByRole("radio", { name: "Slope" }));
    expect(onChange).toHaveBeenCalledWith("slope");
  });

  it("Arrow-Right from Elevation moves focus and selection to Slope", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap3DColorModeToggle mode="elevation" onChange={onChange} />);
    const elev = screen.getByRole("radio", { name: "Elevation" });
    elev.focus();
    await user.keyboard("{ArrowRight}");
    expect(onChange).toHaveBeenCalledWith("slope");
  });

  it("Arrow-Left from Slope wraps focus + selection back to Elevation", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap3DColorModeToggle mode="slope" onChange={onChange} />);
    const slope = screen.getByRole("radio", { name: "Slope" });
    slope.focus();
    await user.keyboard("{ArrowLeft}");
    expect(onChange).toHaveBeenCalledWith("elevation");
  });

  it("the active radio has tabIndex 0 and the inactive radio has tabIndex -1 (roving)", () => {
    render(<ParcelMap3DColorModeToggle mode="elevation" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("radio", { name: "Slope" })).toHaveAttribute("tabindex", "-1");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/components/auction/ParcelMap3DColorModeToggle.test.tsx
```

Expected: all 7 tests fail with `Cannot find module './ParcelMap3DColorModeToggle'`.

- [ ] **Step 3: Implement the toggle**

Create `frontend/src/components/auction/ParcelMap3DColorModeToggle.tsx`:

```tsx
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
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend && npx vitest run src/components/auction/ParcelMap3DColorModeToggle.test.tsx
```

Expected: all 7 tests pass.

- [ ] **Step 5: Per-task verification**

```bash
cd frontend && npm run build && npm test && npm run verify
```

Expected: build green, full suite green, verify green.

- [ ] **Step 6: Commit and push**

```bash
git add frontend/src/components/auction/ParcelMap3DColorModeToggle.tsx frontend/src/components/auction/ParcelMap3DColorModeToggle.test.tsx
git commit -m "feat(frontend): parcel map 3D color mode toggle"
git push
```

---

## Task 5: Wire ParcelMap3D + open PR + merge

Final task: wires the hook + toggle into `ParcelMap3D`, replaces the Task 3 placeholder slope grid with a real `useMemo`, opens and merges the PR.

**Files:**
- Modify: `frontend/src/components/auction/ParcelMap3D.tsx`
- Modify: `frontend/src/components/auction/ParcelMap3D.test.tsx`

- [ ] **Step 1: Update the failing test in `ParcelMap3D.test.tsx`**

Open `frontend/src/components/auction/ParcelMap3D.test.tsx` and add a new case at the bottom of the `describe("ParcelMap3D", ...)` block. Use Edit. The new case asserts the toggle renders when scan data is available and that clicking it persists the new mode to localStorage:

```tsx
it("renders the color mode toggle once scan data is loaded, defaulting to elevation", async () => {
  vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(scanPayload());
  vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
  wrap(<ParcelMap3D publicId={publicId} />);
  const group = await screen.findByRole("radiogroup", { name: "Color by" });
  expect(group).toBeInTheDocument();
  expect(screen.getByRole("radio", { name: "Elevation" })).toHaveAttribute("aria-checked", "true");
  expect(screen.getByRole("radio", { name: "Slope" })).toHaveAttribute("aria-checked", "false");
});

it("hides the color mode toggle during the loading skeleton state", () => {
  vi.spyOn(parcelScanApi, "getParcelScan").mockImplementation(() => new Promise(() => {}));
  vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
  wrap(<ParcelMap3D publicId={publicId} />);
  expect(screen.queryByRole("radiogroup", { name: "Color by" })).toBeNull();
});

it("clicking the Slope radio writes 'slope' to localStorage", async () => {
  const user = userEvent.setup();
  window.localStorage.clear();
  vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(scanPayload());
  vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
  wrap(<ParcelMap3D publicId={publicId} />);
  const slope = await screen.findByRole("radio", { name: "Slope" });
  await user.click(slope);
  expect(window.localStorage.getItem("slpa:parcel-map:3d-color")).toBe("slope");
});
```

If the test file does not already import `userEvent`, add this near the top:

```tsx
import userEvent from "@testing-library/user-event";
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/components/auction/ParcelMap3D.test.tsx
```

Expected: the three new cases fail (no radiogroup yet); the existing cases still pass.

- [ ] **Step 3: Wire `useParcelMapColorMode` + slope grid + toggle into `ParcelMap3D.tsx`**

Edit `frontend/src/components/auction/ParcelMap3D.tsx`:

3a. Add imports near the existing imports block:

```tsx
import { useParcelMapColorMode } from "@/hooks/useParcelMapColorMode";
import { ParcelMap3DColorModeToggle } from "./ParcelMap3DColorModeToggle";
import {
  // ... existing imports kept
  computeSlopeGrid,
} from "@/lib/parcelMap3D/geometry";
```

3b. Inside the component body, near the other hook calls (`useParcelScan`, etc.), add:

```tsx
const [colorMode, setColorMode] = useParcelMapColorMode();
```

3c. Add a memo for the slope grid right after the `upsampledGrid` memo:

```tsx
const slopeGrid = useMemo(() => {
  if (!upsampledGrid) return null;
  return computeSlopeGrid(upsampledGrid);
}, [upsampledGrid]);
```

3d. Update the `meshGeometry` memo to use the real `slopeGrid`, the real `floorY`, and the current `colorMode`:

```tsx
const meshGeometry = useMemo(() => {
  if (!upsampledGrid || !stats || !bounds || !slopeGrid) return null;
  return buildHeightfieldGeometry(
    upsampledGrid,
    stats.parcelMin,
    bounds.rMin - 8,
    colorMode,
    slopeGrid,
  );
}, [upsampledGrid, stats, bounds, colorMode, slopeGrid]);
```

3e. Add `slopeGrid` to the early-return check (the existing guard that rejects rendering when any memo is null):

```tsx
if (
  isError || !data || !decoded || !stats || !bounds
  || !meshGeometry || !perimeterPoints || !camera || !slopeGrid
) {
  return null;
}
```

3f. Remove any `UPSAMPLED_GRID` import that was added in Task 3 for the placeholder — it's no longer needed.

3g. Inside the wrapping `<div>` of the success-path render branch (the one with `role="img"`), add the toggle absolute-positioned top-right. Wrap the `<Canvas>` and the toggle in a relative container if not already:

```tsx
return (
  <div
    role="img"
    aria-label="Interactive 3D region and parcel elevation map"
    className={cn(
      "relative aspect-square w-full max-w-[320px] bg-bg-subtle border border-border-subtle",
      className,
    )}
  >
    <Canvas>
      {/* unchanged */}
    </Canvas>
    <div className="absolute top-2 right-2">
      <ParcelMap3DColorModeToggle mode={colorMode} onChange={setColorMode} />
    </div>
  </div>
);
```

The `relative` className is added to the wrapping `<div>` so the absolute-positioned toggle is positioned against it. If the wrapper already has positioning context, skip the `relative` addition.

Use Edit for all sub-steps.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend && npx vitest run src/components/auction/ParcelMap3D.test.tsx
```

Expected: all tests pass — the new toggle cases plus all the existing ones.

- [ ] **Step 5: Full frontend verification**

```bash
cd frontend && npm run build && npm test && npm run lint && npm run verify
```

Expected: build green, full Vitest suite green (count grows by the new cases), ESLint clean, all four verify guards green.

- [ ] **Step 6: Commit and push**

```bash
git add frontend/src/components/auction/ParcelMap3D.tsx frontend/src/components/auction/ParcelMap3D.test.tsx
git commit -m "feat(frontend): wire color mode toggle + slope grid into ParcelMap3D"
git push
```

- [ ] **Step 7: Open the PR `feat/parcel-map-3d-slope-and-solid` -> `dev`**

```bash
gh pr create --base dev --head feat/parcel-map-3d-slope-and-solid \
  --title "feat(frontend): 3D parcel map slope mode + solid look" \
  --body @- <<'EOF'
Two enhancements to the parcel-map 3D view, plus a cascading color simplification.

## What ships

- **Slope-shaded color mode.** Inline button group inside the 3D view toggles between Elevation (existing) and Slope (new). Slope colors come from a closed-form finite-difference helper: flat = green, 45deg = red. Preference persists via localStorage (`slpa:parcel-map:3d-color`).
- **Solid look.** Four extruded perimeter walls reach 8m below the lowest cell, wall color = terrain color * 0.55 (recolors in lockstep with the mode), bottom plane at `regionMin - 8m` in a neutral earth tone. Adds ~1k triangles to the existing ~33k.
- **2-stop gradient.** Shared `gradientColor` helper switches from 3-stop (green/yellow/red) to smooth 2-stop (green/red). Both 2D map and 3D modes use the new gradient; the 2D legend simplifies to two stops and the `+4 m` axis label is removed.

## Out of scope (tracked in #414)

- Water plane at SL sea level.
- Snapshot-as-ground-texture using `parcel.snapshotUrl`.
- Parcel-specific walls (this PR adds REGION walls, not the listed-parcel-only walls noted in #414).

## Verification

- `npm run build && npm test && npm run lint && npm run verify` green locally.

## Spec / plan

- Spec: `docs/superpowers/specs/2026-05-24-parcel-map-3d-slope-and-solid-design.md`
- Plan: `docs/superpowers/plans/2026-05-24-parcel-map-3d-slope-and-solid-plan.md`
EOF
```

- [ ] **Step 8: Merge the PR into `dev`**

```bash
gh pr merge --merge feat/parcel-map-3d-slope-and-solid
git checkout dev && git pull --ff-only
```

Do NOT touch the `dev` -> `main` PR — the user merges that themselves.

---

## Self-review

**1. Spec coverage**

| Spec section | Task(s) |
|---|---|
| §3.1 Inline button group placement | Task 4 (component), Task 5 (placement inside scene wrapper) |
| §3.2 ARIA radio group | Task 4 (component implementation + tests) |
| §3.3 localStorage persistence (`slpa:parcel-map:3d-color`, default "elevation", two-phase mount) | Task 2 (hook) |
| §3.4 Toggle visibility gating | Task 5 (toggle only rendered in success-path branch + Step 1 test) |
| §4.1 Shared 2-stop helper | Task 1 (colors.ts rewrite) |
| §4.2 Elevation mode 2-stop gradient | Task 1 |
| §4.3 Slope mode 2-stop gradient | Task 1 (slopeColor helper) |
| §4.4 Slope value computation via finite difference | Task 3 (computeSlopeGrid) |
| §4.5 2D map legend cascading update | Task 1 (ParcelMapLegend) |
| §5.1 Floor depth (regionMin - 8m) | Task 3 (buildHeightfieldGeometry takes floorY; Task 5 passes `bounds.rMin - 8`) |
| §5.2 Walls (4 sides, 128 quads each, top edge follows terrain, outward normals) | Task 3 (emitWall helper + tests) |
| §5.3 Floor (4 verts, downward normal, fixed earth tone) | Task 3 (floor block + tests) |
| §5.4 Geometry overhead | Task 3 (test asserts total vertex + index counts) |
| §6 File list | Task list above matches 1:1 |
| §7 Testing | Each task ships its own tests |
| §8 Out of scope | PR body references #414 |
| §10 Decision log | Implicit in implementation |

All spec sections covered.

**2. Placeholder scan**

No `TBD`, `TODO`, `implement later`, or vague directives. Every step has an exact command or code block. The "temporarily pass placeholder slope grid" in Task 3 Step 6 is intentional scaffolding with a defined removal point in Task 5 Step 3f.

**3. Type consistency**

- `useParcelMapColorMode()` returns `[ParcelMap3DColorMode, (next: ParcelMap3DColorMode) => void]` in Task 2 implementation, Task 2 test, Task 4 import, and Task 5 wiring.
- `ParcelMap3DColorMode = "elevation" | "slope"` consistent across hook, toggle, geometry.
- `buildHeightfieldGeometry(upsampled, parcelMin, floorY, mode, slopeGrid)` signature consistent in Task 3 implementation, Task 3 test, Task 3 Step 6 placeholder, and Task 5 real wiring.
- `computeSlopeGrid(upsampled): Float32Array` signature consistent in Task 3 implementation, Task 3 test, Task 5 memo.
- `STORAGE_KEY = "slpa:parcel-map:3d-color"` consistent across hook, hook test, ParcelMap3D test.
- Wall vertex layout (south, north, west, east in that order, pair ordering by perimeter index) consistent between Task 3 implementation comment block and Task 3 test wall-normal assertions.

No drift.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-24-parcel-map-3d-slope-and-solid-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
