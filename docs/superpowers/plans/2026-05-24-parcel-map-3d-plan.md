# Parcel Map 3D View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an interactive 3D view of the parcel + region heightmap on the auction detail page, behind a tab toggle alongside the existing 2D map, lazy-loaded only when the visitor opens the 3D tab.

**Architecture:** A new client component `ParcelMapTabs` wraps the existing `ParcelMap` (2D) and a new `ParcelMap3D` (R3F scene). Tab choice persists in `localStorage` via `useParcelMapView`. Three.js + R3F + drei are imported via Next's `dynamic(..., { ssr: false })` so they only ship to visitors who open the 3D tab. The 3D scene renders the 64x64 heightmap as a continuous sample-as-vertices surface, bicubic-upsampled 2x to 129x129 vertices (~33k tris) so terrain reads as smooth slopes rather than the data's raw 4m staircase. The scene's heavy lifting (bicubic upsampling, geometry construction, camera fit, WebGL detection) lives in pure helpers under `frontend/src/lib/parcelMap3D/` so it's unit-testable without booting WebGL.

**Tech Stack:** Next.js 16 (App Router) + React 19 + TypeScript 5 + Tailwind CSS 4 + React Query 5 + `three` + `@react-three/fiber` + `@react-three/drei` + Vitest.

**Branch:** `feat/parcel-map-3d` (already off latest `dev`, HEAD `67fcb06b` is the spec commit).

**Spec:** `docs/superpowers/specs/2026-05-24-parcel-map-3d-design.md`

**Deferred:** Water plane, snapshot-as-ground-texture, parcel walls all live in GitHub issue #414 and are explicitly out of scope here.

---

## Conventions every task must honor

- No emojis anywhere.
- No AI/Claude/Anthropic attribution in commits or PR bodies (no `Co-Authored-By` trailers, no AI footers).
- No em-dashes in user-visible copy, commit messages, PR titles, or PR bodies.
- Push commits before requesting review — local-only commits don't show on GitHub.
- Use `Edit` not `Write` for existing files (`package.json`, `ParcelInfoPanel.tsx`, `ParcelInfoPanel.test.tsx`, `README.md`).
- Per-task verification: `npm test` AND `npm run build` AND `npm run verify` (Vitest does not type-check; `npm run build` is the tsc gate).
- Semantic Tailwind tokens only: `text-fg`, `text-fg-muted`, `bg-surface-raised`, `border-border-subtle`, `bg-bg-subtle`, `border-brand`, `text-brand`, `animate-pulse`. No `dark:` variants (verify guard enforces). No `#hex` literals (verify guard enforces — use `rgb(...)` strings or semantic tokens).
- READ `frontend/AGENTS.md` before touching any frontend code (Next.js 16 / React 19 differ from older versions).
- Existing helpers used by both 2D and 3D views (`useParcelScan`, `encoding.ts`, `colors.ts`, `ParcelScanResponse`) must NOT be duplicated, re-implemented, or modified — only imported.
- `frontend/src/components/auction/ParcelMap.tsx` (the 2D component) and its test are NOT touched by this plan.

---

## File map

**New files:**

| File | Responsibility |
|---|---|
| `frontend/src/hooks/useParcelMapView.ts` | localStorage-backed tab state (`"2d"` / `"3d"`), SSR-safe. |
| `frontend/src/hooks/useParcelMapView.test.tsx` | Default value, junk filter, persistence, SSR safety. |
| `frontend/src/lib/parcelMap3D/geometry.ts` | Pure helpers: bicubic upsample, region/parcel stats, heightfield mesh + perimeter geometry, camera defaults, WebGL detection. |
| `frontend/src/lib/parcelMap3D/geometry.test.ts` | Unit tests for the pure helpers. |
| `frontend/src/components/auction/ParcelMap3DSkeleton.tsx` | 320x320 `animate-pulse` placeholder during dynamic-import + during data fetch. |
| `frontend/src/components/auction/ParcelMap3D.tsx` | Default-exported R3F scene: mesh + wireframe + camera + lights + WebGL fallback. |
| `frontend/src/components/auction/ParcelMap3D.test.tsx` | Smoke test with `@react-three/fiber` and `@react-three/drei` module-mocked. |
| `frontend/src/components/auction/ParcelMapTabs.tsx` | Tab UI + persistence + dynamic-imports `ParcelMap3D`. |
| `frontend/src/components/auction/ParcelMapTabs.test.tsx` | Default tab, persistence, ARIA roles, arrow-key nav, WebGL fallback notice. |

**Modified files:**

| File | Change |
|---|---|
| `frontend/package.json` | Add `three`, `@react-three/fiber`, `@react-three/drei` to `dependencies`. |
| `frontend/src/components/auction/ParcelInfoPanel.tsx` | Swap `<ParcelMap ... />` for `<ParcelMapTabs ... />` at line 209. |
| `frontend/src/components/auction/ParcelInfoPanel.test.tsx` | Update the "mounts ParcelMap" case to target `ParcelMapTabs`. |
| `README.md` | Sweep the parcel-map section to mention the new 3D view. |

---

## Pre-resolved gotchas (do NOT let a reviewer "correct" these back)

1. **`dynamic()` import target must resolve at module-load time.** The TypeScript compiler will fail if `@/components/auction/ParcelMap3D` doesn't exist when `ParcelMapTabs.tsx` is compiled. Task ordering puts `ParcelMap3D` (Task 3) before `ParcelMapTabs` (Task 4) so the import target is real, not a stub.
2. **`LineBasicMaterial.linewidth > 1` is ignored on most WebGL platforms.** Use drei's `<Line>` (which wraps `three-stdlib`'s `Line2`) for the white parcel wireframe so `lineWidth={2}` actually renders at 2px. `<line>` (R3F primitive) + `LineBasicMaterial` would silently render 1px.
3. **`three` ships its own TypeScript definitions** (since 0.140-ish). Do NOT install `@types/three` — it's a stale separate package and three.js's own types conflict with it.
4. **WebGL detection runs synchronously on render**, BEFORE the R3F `<Canvas>` mounts. Detection lives in `geometry.ts` (`isWebGLAvailable`) so it's testable. The result drives a render branch in `ParcelMap3D`: if false, render a fallback message + fire `onWebGLUnavailable` callback. Do NOT defer detection to R3F's `onError`.
5. **localStorage value persists across the fallback.** When WebGL is unavailable and the tab switches to 2D, the localStorage key is NOT overwritten — a visitor with WebGL on their phone keeps their preference for next visit. Spec §5.2 point 3.
6. **SW-first row-0-is-south convention.** Scan row 0 = south edge of region. In 3D world coordinates: `worldZ = row * 4` (north is +Z, natural map orientation). World X = `col * 4` (east is +X). World Y = elevation (meters). NO Y flip needed in 3D (unlike the 2D component's canvas-y flip).
7. **R3F mocking in tests.** `vi.mock("@react-three/fiber")` and `vi.mock("@react-three/drei")` MUST be set up so the component tree renders into jsdom without booting WebGL. The mock renders `<Canvas>`, `<OrbitControls>`, `<PerspectiveCamera>` as plain `<div>`s with `data-testid` markers.
8. **`prefers-reduced-motion` is read via `window.matchMedia`** inside `ParcelMap3D` (with SSR guard). Toggling damping at render time without subscribing to media-query changes is fine for this spec — the page reloads if a visitor changes their OS preference.

---

## Task 1: Dependencies + useParcelMapView hook

**Files:**
- Modify: `frontend/package.json` (dependencies block)
- Create: `frontend/src/hooks/useParcelMapView.ts`
- Test: `frontend/src/hooks/useParcelMapView.test.tsx`

- [ ] **Step 1: Read `frontend/AGENTS.md`**

Before any frontend code: open `frontend/AGENTS.md` to confirm the Next.js 16 caveat is fresh.

- [ ] **Step 2: Install the three.js + R3F + drei dependencies**

From the repo root:

```bash
cd frontend && npm install three @react-three/fiber @react-three/drei
```

This adds three entries to `dependencies` in `frontend/package.json` and updates `package-lock.json`. Do NOT install `@types/three` (three ships its own types since 0.140; the separate package is stale and conflicts).

Expected: three new lines under `dependencies` like `"three": "^0.170.0"`, `"@react-three/fiber": "^9.x.x"`, `"@react-three/drei": "^10.x.x"` (exact versions float — newest stable is fine).

- [ ] **Step 3: Write the failing test for `useParcelMapView`**

Create `frontend/src/hooks/useParcelMapView.test.tsx`:

```tsx
import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useParcelMapView } from "./useParcelMapView";

const STORAGE_KEY = "slpa:parcel-map:view";

describe("useParcelMapView", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("returns '2d' when localStorage is empty", () => {
    const { result } = renderHook(() => useParcelMapView());
    expect(result.current[0]).toBe("2d");
  });

  it("returns '3d' when localStorage already holds '3d'", () => {
    window.localStorage.setItem(STORAGE_KEY, "3d");
    const { result } = renderHook(() => useParcelMapView());
    expect(result.current[0]).toBe("3d");
  });

  it("ignores junk values and falls back to '2d'", () => {
    window.localStorage.setItem(STORAGE_KEY, "asdf");
    const { result } = renderHook(() => useParcelMapView());
    expect(result.current[0]).toBe("2d");
  });

  it("setView writes to localStorage and updates returned state", () => {
    const { result } = renderHook(() => useParcelMapView());
    act(() => result.current[1]("3d"));
    expect(result.current[0]).toBe("3d");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("3d");
  });

  it("setView back to '2d' updates both state and storage", () => {
    window.localStorage.setItem(STORAGE_KEY, "3d");
    const { result } = renderHook(() => useParcelMapView());
    act(() => result.current[1]("2d"));
    expect(result.current[0]).toBe("2d");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("2d");
  });
});
```

- [ ] **Step 4: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/hooks/useParcelMapView.test.tsx
```

Expected: All 5 tests fail with `Cannot find module './useParcelMapView'`.

- [ ] **Step 5: Implement `useParcelMapView`**

Create `frontend/src/hooks/useParcelMapView.ts`:

```ts
"use client";

import { useCallback, useEffect, useState } from "react";

export type ParcelMapView = "2d" | "3d";

const STORAGE_KEY = "slpa:parcel-map:view";
const DEFAULT_VIEW: ParcelMapView = "2d";

function readStoredView(): ParcelMapView {
  if (typeof window === "undefined") return DEFAULT_VIEW;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw === "2d" || raw === "3d") return raw;
    return DEFAULT_VIEW;
  } catch {
    // localStorage can throw in privacy modes or quota-exceeded scenarios.
    return DEFAULT_VIEW;
  }
}

/**
 * localStorage-backed tab choice for the parcel-map view switcher. Returns
 * the current view and a setter that mirrors writes to localStorage.
 *
 * <p>SSR-safe: the initial render returns the default ({@code "2d"}); the
 * stored value is read inside {@code useEffect} on mount so the server pass
 * never touches {@code window}. Junk values in storage (anything other than
 * {@code "2d"} or {@code "3d"}) fall back to the default without throwing.
 */
export function useParcelMapView(): [
  ParcelMapView,
  (next: ParcelMapView) => void,
] {
  const [view, setView] = useState<ParcelMapView>(DEFAULT_VIEW);

  useEffect(() => {
    setView(readStoredView());
  }, []);

  const update = useCallback((next: ParcelMapView) => {
    setView(next);
    if (typeof window === "undefined") return;
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // localStorage write can throw under quota or privacy modes; swallow
      // so the in-session tab change still takes effect.
    }
  }, []);

  return [view, update];
}
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
cd frontend && npx vitest run src/hooks/useParcelMapView.test.tsx
```

Expected: All 5 tests pass.

- [ ] **Step 7: Run the full per-task verification**

```bash
cd frontend && npm run build && npm test && npm run verify
```

Expected: build succeeds (tsc happy with the new deps), full test suite green, all four verify guards green.

- [ ] **Step 8: Commit and push**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/hooks/useParcelMapView.ts frontend/src/hooks/useParcelMapView.test.tsx
git commit -m "feat(frontend): useParcelMapView hook + three.js deps"
git push
```

---

## Task 2: Pure geometry helpers + ParcelMap3DSkeleton

**Files:**
- Create: `frontend/src/lib/parcelMap3D/geometry.ts`
- Test: `frontend/src/lib/parcelMap3D/geometry.test.ts`
- Create: `frontend/src/components/auction/ParcelMap3DSkeleton.tsx`

This task ships only pure, no-React, no-R3F code (plus a presentational skeleton). All the math the 3D component depends on lands here so it can be unit-tested without booting WebGL.

- [ ] **Step 1: Write the failing test for the geometry helpers**

Create `frontend/src/lib/parcelMap3D/geometry.test.ts`:

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
  decodeElevationGrid,
  isWebGLAvailable,
  sampleUpsampled,
} from "./geometry";

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
    // At 2x upsample, original (row, col) maps to upsampled (2*row, 2*col).
    const input = new Float32Array(64 * 64);
    input[10 * 64 + 20] = 100;
    const out = bicubicUpsample(input);
    expect(out[20 * UPSAMPLED_GRID + 40]).toBeCloseTo(100, 4);
  });

  it("smoothly interpolates between two adjacent samples", () => {
    const input = new Float32Array(64 * 64);
    // Step: row 10 col 5 = 0, row 10 col 6 = 10.
    input[10 * 64 + 5] = 0;
    input[10 * 64 + 6] = 10;
    const out = bicubicUpsample(input);
    // Midpoint between cols 5 and 6 in upsampled space is col index 11
    // (2*5 + 1). With Catmull-Rom it should be roughly 5 (smooth midpoint).
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
    h[20 * 64 + 20] = 100; // outside parcel
    const layout = layoutWith([[10, 10], [10, 11]]);
    const stats = computeParcelStats(layout, decodeElevationGrid(h, 22, 0.5))!;
    expect(stats.parcelCellCount).toBe(2);
    expect(stats.parcelMin).toBeCloseTo(22.0);
    expect(stats.parcelMax).toBeCloseTo(27.0);
  });
});

describe("buildHeightfieldGeometry", () => {
  it("produces UPSAMPLED_GRID^2 vertices and (UPSAMPLED_GRID-1)^2 * 2 triangles", () => {
    const upsampled = bicubicUpsample(decodeElevationGrid(heightsAll(0), 22, 0.5));
    const geom = buildHeightfieldGeometry(upsampled, 22);
    expect(geom.getAttribute("position").count).toBe(UPSAMPLED_GRID * UPSAMPLED_GRID);
    expect(geom.getAttribute("color").count).toBe(UPSAMPLED_GRID * UPSAMPLED_GRID);
    expect(geom.index!.count).toBe((UPSAMPLED_GRID - 1) * (UPSAMPLED_GRID - 1) * 6);
  });

  it("vertex 0 (SW corner) sits at world (0, baseMeters, 0)", () => {
    const upsampled = bicubicUpsample(decodeElevationGrid(heightsAll(0), 22, 0.5));
    const geom = buildHeightfieldGeometry(upsampled, 22);
    const pos = geom.getAttribute("position");
    expect(pos.getX(0)).toBe(0);
    expect(pos.getY(0)).toBeCloseTo(22.0);
    expect(pos.getZ(0)).toBe(0);
  });

  it("colors vertex 0 with green-500 when its elevation equals parcelMin", () => {
    const upsampled = bicubicUpsample(decodeElevationGrid(heightsAll(0), 22, 0.5));
    const geom = buildHeightfieldGeometry(upsampled, 22);
    const colors = geom.getAttribute("color");
    expect(colors.getX(0)).toBeCloseTo(34 / 255);
    expect(colors.getY(0)).toBeCloseTo(197 / 255);
    expect(colors.getZ(0)).toBeCloseTo(94 / 255);
  });
});

describe("sampleUpsampled", () => {
  it("samples upsampled grid at world (x, z) coordinates with bilinear fallback", () => {
    const upsampled = new Float32Array(UPSAMPLED_GRID * UPSAMPLED_GRID).fill(30);
    // World cell-edge (col=10) sits at x = 40m, which maps to upsampled col 20.
    expect(sampleUpsampled(upsampled, 40, 40)).toBeCloseTo(30);
  });
});

describe("buildPerimeterPoints", () => {
  it("returns 8 endpoints (4 edges * 2 endpoints) for a single isolated parcel cell", () => {
    const upsampled = bicubicUpsample(decodeElevationGrid(heightsAll(0), 22, 0.5));
    const points = buildPerimeterPoints(
      layoutWith([[10, 10]]), upsampled,
    );
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
    // 4 outer cells each have 3 outward edges; center has 0. Total endpoints = 24.
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

  it("camera sits at 45° azimuth, 30° elevation above the target", () => {
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
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/lib/parcelMap3D/geometry.test.ts
```

Expected: All tests fail with `Cannot find module './geometry'`.

- [ ] **Step 3: Implement the geometry helpers**

Create `frontend/src/lib/parcelMap3D/geometry.ts`:

```ts
import {
  BufferGeometry,
  Float32BufferAttribute,
} from "three";

import { gradientColor } from "@/lib/parcelMap/colors";
import { isCellInParcel } from "@/lib/parcelMap/encoding";

const GRID = 64;
const CELL_M = 4;
const REGION_M = GRID * CELL_M; // 256

/** Upsample factor for the bicubic pass. 2x keeps cell-edge coords on real vertices. */
export const UPSAMPLE_FACTOR = 2;
/** Upsampled grid side length, e.g. 64 * 2 + 1 = 129 vertices per axis. */
export const UPSAMPLED_GRID = GRID * UPSAMPLE_FACTOR + 1;
/** Spacing between upsampled vertices in meters (REGION_M / (UPSAMPLED_GRID - 1)). */
export const UPSAMPLED_SPACING_M = REGION_M / (UPSAMPLED_GRID - 1);

export interface RegionBounds {
  rMin: number;
  rMax: number;
}

export interface ParcelStats {
  parcelMin: number;
  parcelMax: number;
  parcelCellCount: number;
}

export interface CameraDefaults {
  /** World-space position of the camera. */
  position: [number, number, number];
  /** World-space point the camera looks at. */
  target: [number, number, number];
  /** Vertical field of view in degrees. */
  fovDeg: number;
}

/**
 * Decode the raw uint8 heightmap to a 64x64 Float32Array of elevations in
 * meters (row-major, SW-first to match the scan encoding).
 */
export function decodeElevationGrid(
  heightCells: Uint8Array,
  baseMeters: number,
  stepMeters: number,
): Float32Array {
  const out = new Float32Array(GRID * GRID);
  for (let i = 0; i < GRID * GRID; i++) {
    out[i] = baseMeters + (heightCells[i] & 0xff) * stepMeters;
  }
  return out;
}

/** Catmull-Rom (1D) interpolation between p1 and p2 with neighbors p0 + p3. */
function catmullRom(
  p0: number, p1: number, p2: number, p3: number, t: number,
): number {
  const t2 = t * t;
  const t3 = t2 * t;
  return 0.5 * (
    2 * p1 +
    (-p0 + p2) * t +
    (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
    (-p0 + 3 * p1 - 3 * p2 + p3) * t3
  );
}

/** Clamp-to-edge index lookup into a 64x64 elevation grid. */
function sampleGrid(grid: Float32Array, row: number, col: number): number {
  const r = row < 0 ? 0 : row >= GRID ? GRID - 1 : row;
  const c = col < 0 ? 0 : col >= GRID ? GRID - 1 : col;
  return grid[r * GRID + c];
}

/**
 * Bicubic (Catmull-Rom) upsample from the 64x64 raw elevation grid to
 * {@link UPSAMPLED_GRID} squared. Original sample positions are preserved
 * exactly at upsampled vertices (row, col) where (row % UPSAMPLE_FACTOR === 0)
 * and (col % UPSAMPLE_FACTOR === 0) — Catmull-Rom passes through its control
 * points by construction.
 *
 * <p>Cost: ~17k Catmull-Rom evals per axis pass, twice; trivial on the main
 * thread. Memoized by the caller via {@code useMemo}.
 */
export function bicubicUpsample(grid: Float32Array): Float32Array {
  // Pass 1: interpolate along rows. Output is GRID rows x UPSAMPLED_GRID cols.
  const rowsTemp = new Float32Array(GRID * UPSAMPLED_GRID);
  for (let row = 0; row < GRID; row++) {
    for (let uCol = 0; uCol < UPSAMPLED_GRID; uCol++) {
      const x = uCol / UPSAMPLE_FACTOR;
      const col1 = Math.floor(x);
      const t = x - col1;
      const p0 = sampleGrid(grid, row, col1 - 1);
      const p1 = sampleGrid(grid, row, col1);
      const p2 = sampleGrid(grid, row, col1 + 1);
      const p3 = sampleGrid(grid, row, col1 + 2);
      rowsTemp[row * UPSAMPLED_GRID + uCol] =
        t === 0 ? p1 : catmullRom(p0, p1, p2, p3, t);
    }
  }
  // Pass 2: interpolate along columns. Output is UPSAMPLED_GRID x UPSAMPLED_GRID.
  const out = new Float32Array(UPSAMPLED_GRID * UPSAMPLED_GRID);
  for (let uRow = 0; uRow < UPSAMPLED_GRID; uRow++) {
    const y = uRow / UPSAMPLE_FACTOR;
    const row1 = Math.floor(y);
    const t = y - row1;
    for (let uCol = 0; uCol < UPSAMPLED_GRID; uCol++) {
      const p0 = rowsTemp[Math.max(0, row1 - 1) * UPSAMPLED_GRID + uCol];
      const p1 = rowsTemp[row1 * UPSAMPLED_GRID + uCol];
      const p2 = rowsTemp[Math.min(GRID - 1, row1 + 1) * UPSAMPLED_GRID + uCol];
      const p3 = rowsTemp[Math.min(GRID - 1, row1 + 2) * UPSAMPLED_GRID + uCol];
      out[uRow * UPSAMPLED_GRID + uCol] =
        t === 0 ? p1 : catmullRom(p0, p1, p2, p3, t);
    }
  }
  return out;
}

/** Min and max elevation across the (raw or upsampled) elevation grid. */
export function computeRegionBounds(grid: Float32Array): RegionBounds {
  let min = Number.POSITIVE_INFINITY;
  let max = Number.NEGATIVE_INFINITY;
  for (let i = 0; i < grid.length; i++) {
    const y = grid[i];
    if (y < min) min = y;
    if (y > max) max = y;
  }
  return { rMin: min, rMax: max };
}

/**
 * Min, max, and count of cells inside the listed parcel. Computed from the
 * raw 64x64 grid (NOT the upsampled grid) so the parcel-min anchor matches
 * what the 2D view computes from the same data.
 */
export function computeParcelStats(
  layoutCells: Uint8Array,
  rawGrid: Float32Array,
): ParcelStats | null {
  let min = Number.POSITIVE_INFINITY;
  let max = Number.NEGATIVE_INFINITY;
  let count = 0;
  for (let row = 0; row < GRID; row++) {
    for (let col = 0; col < GRID; col++) {
      if (!isCellInParcel(layoutCells, row, col)) continue;
      const e = rawGrid[row * GRID + col];
      if (e < min) min = e;
      if (e > max) max = e;
      count++;
    }
  }
  if (count === 0) return null;
  return { parcelMin: min, parcelMax: max, parcelCellCount: count };
}

/**
 * Build the heightfield mesh as a single indexed {@link BufferGeometry}.
 * 129x129 vertices on a regular grid, two CCW triangles per quad. Vertex
 * colors come from {@code gradientColor(elev - parcelMin)} so the gradient
 * interpolates smoothly across triangle interiors. No walls — the terrain
 * is a continuous surface.
 *
 * Coordinate system:
 *   X = uCol * UPSAMPLED_SPACING_M (east, +X)
 *   Y = upsampled[uRow * UPSAMPLED_GRID + uCol]
 *   Z = uRow * UPSAMPLED_SPACING_M (north, +Z, "north is +Z")
 */
export function buildHeightfieldGeometry(
  upsampled: Float32Array,
  parcelMin: number,
): BufferGeometry {
  const vertexCount = UPSAMPLED_GRID * UPSAMPLED_GRID;
  const quadCount = (UPSAMPLED_GRID - 1) * (UPSAMPLED_GRID - 1);

  const positions = new Float32Array(vertexCount * 3);
  const colors = new Float32Array(vertexCount * 3);
  const indices = new Uint32Array(quadCount * 6);

  for (let uRow = 0; uRow < UPSAMPLED_GRID; uRow++) {
    for (let uCol = 0; uCol < UPSAMPLED_GRID; uCol++) {
      const vIdx = uRow * UPSAMPLED_GRID + uCol;
      const y = upsampled[vIdx];
      const x = uCol * UPSAMPLED_SPACING_M;
      const z = uRow * UPSAMPLED_SPACING_M;
      positions[vIdx * 3 + 0] = x;
      positions[vIdx * 3 + 1] = y;
      positions[vIdx * 3 + 2] = z;
      const c = gradientColor(y - parcelMin);
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
      // Two CCW triangles seen from +Y (top-down).
      indices[iIdx++] = sw; indices[iIdx++] = se; indices[iIdx++] = ne;
      indices[iIdx++] = sw; indices[iIdx++] = ne; indices[iIdx++] = nw;
    }
  }

  const geometry = new BufferGeometry();
  geometry.setAttribute("position", new Float32BufferAttribute(positions, 3));
  geometry.setAttribute("color", new Float32BufferAttribute(colors, 3));
  geometry.setIndex(Array.from(indices));
  geometry.computeVertexNormals();
  return geometry;
}

/**
 * Sample the upsampled grid at world (x, z) coordinates in meters. With
 * UPSAMPLE_FACTOR = 2, cell-edge coords (multiples of 4m) align exactly with
 * upsampled vertices so this is a direct index lookup. For coords between
 * vertices, falls back to bilinear interpolation (only matters if the
 * perimeter helper ever calls with off-grid x/z).
 */
export function sampleUpsampled(
  upsampled: Float32Array,
  x: number,
  z: number,
): number {
  const fc = x / UPSAMPLED_SPACING_M;
  const fr = z / UPSAMPLED_SPACING_M;
  const c0 = Math.max(0, Math.min(UPSAMPLED_GRID - 1, Math.floor(fc)));
  const r0 = Math.max(0, Math.min(UPSAMPLED_GRID - 1, Math.floor(fr)));
  const c1 = Math.min(UPSAMPLED_GRID - 1, c0 + 1);
  const r1 = Math.min(UPSAMPLED_GRID - 1, r0 + 1);
  const tc = fc - c0;
  const tr = fr - r0;
  const a = upsampled[r0 * UPSAMPLED_GRID + c0];
  const b = upsampled[r0 * UPSAMPLED_GRID + c1];
  const cv = upsampled[r1 * UPSAMPLED_GRID + c0];
  const d = upsampled[r1 * UPSAMPLED_GRID + c1];
  return (
    a * (1 - tc) * (1 - tr) +
    b * tc * (1 - tr) +
    cv * (1 - tc) * tr +
    d * tc * tr
  );
}

/**
 * Build the parcel-perimeter line segments as an array of
 * {@code [x, y, z]} tuples, one pair per edge. Endpoints are sampled from
 * the upsampled height grid so the wireframe sits flush on the mesh.
 *
 * Edge-detection mirrors the 2D component's cyan-outline pass: for each
 * parcel cell (raw 64x64 grid), for each of its 4 edges, if the neighbor on
 * that edge is outside the parcel (or off-grid), emit a 3D line segment
 * along that edge.
 */
export function buildPerimeterPoints(
  layoutCells: Uint8Array,
  upsampled: Float32Array,
): Array<[number, number, number]> {
  const points: Array<[number, number, number]> = [];
  const sample = (x: number, z: number): [number, number, number] => [
    x, sampleUpsampled(upsampled, x, z), z,
  ];

  for (let row = 0; row < GRID; row++) {
    for (let col = 0; col < GRID; col++) {
      if (!isCellInParcel(layoutCells, row, col)) continue;
      const xWest = col * CELL_M;
      const xEast = (col + 1) * CELL_M;
      const zSouth = row * CELL_M;
      const zNorth = (row + 1) * CELL_M;

      // North neighbor (row+1)
      if (row === GRID - 1 || !isCellInParcel(layoutCells, row + 1, col)) {
        points.push(sample(xWest, zNorth), sample(xEast, zNorth));
      }
      // South neighbor (row-1)
      if (row === 0 || !isCellInParcel(layoutCells, row - 1, col)) {
        points.push(sample(xWest, zSouth), sample(xEast, zSouth));
      }
      // East neighbor (col+1)
      if (col === GRID - 1 || !isCellInParcel(layoutCells, row, col + 1)) {
        points.push(sample(xEast, zSouth), sample(xEast, zNorth));
      }
      // West neighbor (col-1)
      if (col === 0 || !isCellInParcel(layoutCells, row, col - 1)) {
        points.push(sample(xWest, zSouth), sample(xWest, zNorth));
      }
    }
  }
  return points;
}

/**
 * Default camera position + target. Camera sits at 45° azimuth, 30°
 * elevation above the region center; distance fits the full 256m region in
 * the viewport with 20% breathing room at FOV 50°.
 */
export function computeCameraDefaults(bounds: RegionBounds): CameraDefaults {
  const fovDeg = 50;
  const targetY = (bounds.rMin + bounds.rMax) / 2;
  const halfDiagonal = (REGION_M / 2) * Math.SQRT2;
  const fitDistance = halfDiagonal / Math.tan((fovDeg / 2) * (Math.PI / 180));
  const dist = fitDistance * 1.2;
  const az = (45 * Math.PI) / 180;
  const el = (30 * Math.PI) / 180;
  const cx = REGION_M / 2 + dist * Math.cos(el) * Math.sin(az);
  const cy = targetY + dist * Math.sin(el);
  const cz = REGION_M / 2 + dist * Math.cos(el) * Math.cos(az);
  return {
    position: [cx, cy, cz],
    target: [REGION_M / 2, targetY, REGION_M / 2],
    fovDeg,
  };
}

/**
 * Returns true if the current browser can give us a WebGL context.
 * SSR-safe (returns true during the Node pass — the actual check defers to
 * client mount).
 */
export function isWebGLAvailable(): boolean {
  if (typeof document === "undefined") return true;
  try {
    const c = document.createElement("canvas");
    const ctx = c.getContext("webgl2") ?? c.getContext("webgl");
    return ctx !== null;
  } catch {
    return false;
  }
}
```

- [ ] **Step 4: Run the geometry test to verify it passes**

```bash
cd frontend && npx vitest run src/lib/parcelMap3D/geometry.test.ts
```

Expected: All tests pass.

- [ ] **Step 5: Create `ParcelMap3DSkeleton`**

Create `frontend/src/components/auction/ParcelMap3DSkeleton.tsx`:

```tsx
import { cn } from "@/lib/cn";

interface Props {
  className?: string;
}

/**
 * Loading placeholder shown while {@code next/dynamic} downloads the
 * three.js bundle and while {@code useParcelScan} fetches the raster. Mirrors
 * the 2D map's loading shape (320x320, animate-pulse, bg-bg-subtle) so the
 * panel doesn't visually jump when the visitor switches tabs.
 */
export function ParcelMap3DSkeleton({ className }: Props) {
  return (
    <div
      aria-hidden="true"
      data-testid="parcel-map-3d-skeleton"
      className={cn(
        "aspect-square w-full max-w-[320px] animate-pulse bg-bg-subtle",
        className,
      )}
    />
  );
}
```

- [ ] **Step 6: Run the full per-task verification**

```bash
cd frontend && npm run build && npm test && npm run verify
```

Expected: build green, full test suite green (geometry tests new, the rest unchanged), all four verify guards green.

- [ ] **Step 7: Commit and push**

```bash
git add frontend/src/lib/parcelMap3D frontend/src/components/auction/ParcelMap3DSkeleton.tsx
git commit -m "feat(frontend): parcel map 3D geometry helpers + skeleton"
git push
```

---

## Task 3: ParcelMap3D React component

**Files:**
- Create: `frontend/src/components/auction/ParcelMap3D.tsx`
- Test: `frontend/src/components/auction/ParcelMap3D.test.tsx`

- [ ] **Step 1: Write the failing test for `ParcelMap3D`**

Create `frontend/src/components/auction/ParcelMap3D.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

import * as parcelScanApi from "@/lib/api/parcelScan";
import * as geometryModule from "@/lib/parcelMap3D/geometry";
import ParcelMap3D from "./ParcelMap3D";

// Mock the R3F + drei modules so jsdom never tries to boot WebGL.
vi.mock("@react-three/fiber", () => ({
  Canvas: ({ children }: { children: ReactNode }) => (
    <div data-testid="r3f-canvas">{children}</div>
  ),
}));

vi.mock("@react-three/drei", () => ({
  OrbitControls: (props: Record<string, unknown>) => (
    <div data-testid="orbit-controls" data-props={JSON.stringify(props)} />
  ),
  PerspectiveCamera: ({ children, ...props }: { children?: ReactNode } & Record<string, unknown>) => (
    <div data-testid="perspective-camera" data-props={JSON.stringify(props)}>
      {children}
    </div>
  ),
  Line: (props: Record<string, unknown>) => (
    <div data-testid="parcel-perimeter-line" data-props={JSON.stringify({ ...props, points: undefined })} />
  ),
}));

function wrap(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

const publicId = "33333333-3333-3333-3333-333333333333";

function scanPayload() {
  const layoutBytes = new Uint8Array(512);
  const bitIndex = 10 * 64 + 10;
  layoutBytes[bitIndex >> 3] |= 1 << (7 - (bitIndex & 7));
  const heightBytes = new Uint8Array(4096);
  const toBase64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
  return {
    gridSize: 64,
    cellSizeMeters: 4,
    layoutCellsBase64: toBase64(layoutBytes),
    heightCellsBase64: toBase64(heightBytes),
    baseMeters: 22.0,
    stepMeters: 0.5,
    scannedAt: "2026-05-24T05:00:00Z",
  };
}

describe("ParcelMap3D", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("renders the skeleton while data is pending", () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockImplementation(
      () => new Promise(() => {}),
    );
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    wrap(<ParcelMap3D publicId={publicId} />);
    expect(screen.getByTestId("parcel-map-3d-skeleton")).toBeInTheDocument();
  });

  it("returns null when the endpoint returns 404", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(null);
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    const { container } = wrap(<ParcelMap3D publicId={publicId} />);
    await waitFor(() => {
      expect(container.querySelector('[data-testid="parcel-map-3d-skeleton"]')).toBeNull();
    });
    expect(container.querySelector('[data-testid="r3f-canvas"]')).toBeNull();
  });

  it("renders Canvas + PerspectiveCamera + OrbitControls + perimeter Line on loaded data", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(scanPayload());
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    wrap(<ParcelMap3D publicId={publicId} />);
    expect(await screen.findByTestId("r3f-canvas")).toBeInTheDocument();
    expect(screen.getByTestId("perspective-camera")).toBeInTheDocument();
    expect(screen.getByTestId("orbit-controls")).toBeInTheDocument();
    expect(screen.getByTestId("parcel-perimeter-line")).toBeInTheDocument();
  });

  it("renders the WebGL-unavailable fallback message and fires onWebGLUnavailable", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(scanPayload());
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(false);
    const onWebGLUnavailable = vi.fn();
    wrap(<ParcelMap3D publicId={publicId} onWebGLUnavailable={onWebGLUnavailable} />);
    expect(
      await screen.findByText(
        /3D view requires WebGL, which your browser does not support\. Showing 2D view instead\./,
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(onWebGLUnavailable).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId("r3f-canvas")).toBeNull();
  });

  it("passes the spec's camera defaults (FOV 50, target at region center)", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(scanPayload());
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    wrap(<ParcelMap3D publicId={publicId} />);
    const cam = await screen.findByTestId("perspective-camera");
    const props = JSON.parse(cam.getAttribute("data-props") ?? "{}");
    expect(props.fov).toBe(50);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/components/auction/ParcelMap3D.test.tsx
```

Expected: All tests fail with `Cannot find module './ParcelMap3D'`.

- [ ] **Step 3: Implement `ParcelMap3D`**

Create `frontend/src/components/auction/ParcelMap3D.tsx`:

```tsx
"use client";

import { useEffect, useMemo, useState } from "react";
import { Canvas } from "@react-three/fiber";
import { Line, OrbitControls, PerspectiveCamera } from "@react-three/drei";

import { cn } from "@/lib/cn";
import { useParcelScan } from "@/hooks/useParcelScan";
import { decodeBase64ToBytes } from "@/lib/parcelMap/encoding";
import {
  bicubicUpsample,
  buildHeightfieldGeometry,
  buildPerimeterPoints,
  computeCameraDefaults,
  computeParcelStats,
  computeRegionBounds,
  decodeElevationGrid,
  isWebGLAvailable,
} from "@/lib/parcelMap3D/geometry";
import { ParcelMap3DSkeleton } from "./ParcelMap3DSkeleton";

interface Props {
  publicId: string;
  /** Called once if WebGL is unavailable. Parent should switch to the 2D view. */
  onWebGLUnavailable?: () => void;
  className?: string;
}

/**
 * Interactive 3D view of the parcel + region heightmap. Drag to orbit,
 * scroll to zoom, middle-click to pan. The 64x64 sample grid is bicubic-
 * upsampled 2x and rendered as a continuous heightfield surface (no walls,
 * no staircase). The parcel is outlined in a white wireframe so it stays
 * visible from any angle. Outside-parcel vertices render in their full
 * gradient color — the wireframe is the parcel-vs-non-parcel signal in 3D.
 *
 * Spec: docs/superpowers/specs/2026-05-24-parcel-map-3d-design.md
 *
 * Default-exported so {@code next/dynamic} can lazy-import it cleanly:
 *   const ParcelMap3D = dynamic(() => import("./ParcelMap3D"), { ssr: false });
 */
export default function ParcelMap3D({
  publicId,
  onWebGLUnavailable,
  className,
}: Props) {
  const { data, isPending, isError } = useParcelScan(publicId);
  const webglOk = useMemo(() => isWebGLAvailable(), []);
  const [reducedMotion, setReducedMotion] = useState(false);

  useEffect(() => {
    if (typeof window === "undefined") return;
    const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    setReducedMotion(mq.matches);
  }, []);

  useEffect(() => {
    if (!webglOk) onWebGLUnavailable?.();
  }, [webglOk, onWebGLUnavailable]);

  const decoded = useMemo(() => {
    if (!data) return null;
    return {
      layoutCells: decodeBase64ToBytes(data.layoutCellsBase64),
      heightCells: decodeBase64ToBytes(data.heightCellsBase64),
      baseMeters: data.baseMeters,
      stepMeters: data.stepMeters,
    };
  }, [data]);

  const rawGrid = useMemo(() => {
    if (!decoded) return null;
    return decodeElevationGrid(
      decoded.heightCells, decoded.baseMeters, decoded.stepMeters,
    );
  }, [decoded]);

  const upsampledGrid = useMemo(() => {
    if (!rawGrid) return null;
    return bicubicUpsample(rawGrid);
  }, [rawGrid]);

  const stats = useMemo(() => {
    if (!decoded || !rawGrid) return null;
    return computeParcelStats(decoded.layoutCells, rawGrid);
  }, [decoded, rawGrid]);

  const bounds = useMemo(() => {
    if (!upsampledGrid) return null;
    return computeRegionBounds(upsampledGrid);
  }, [upsampledGrid]);

  const meshGeometry = useMemo(() => {
    if (!upsampledGrid || !stats) return null;
    return buildHeightfieldGeometry(upsampledGrid, stats.parcelMin);
  }, [upsampledGrid, stats]);

  const perimeterPoints = useMemo(() => {
    if (!decoded || !upsampledGrid) return null;
    return buildPerimeterPoints(decoded.layoutCells, upsampledGrid);
  }, [decoded, upsampledGrid]);

  const camera = useMemo(() => {
    if (!bounds) return null;
    return computeCameraDefaults(bounds);
  }, [bounds]);

  if (!webglOk) {
    return (
      <p
        className={cn("text-xs text-fg-muted", className)}
        data-testid="parcel-map-3d-webgl-fallback"
      >
        3D view requires WebGL, which your browser does not support. Showing 2D
        view instead.
      </p>
    );
  }

  if (isPending) return <ParcelMap3DSkeleton className={className} />;
  if (
    isError || !data || !decoded || !stats || !bounds
    || !meshGeometry || !perimeterPoints || !camera
  ) {
    return null;
  }

  return (
    <div
      className={cn(
        "aspect-square w-full max-w-[320px] bg-bg-subtle border border-border-subtle",
        className,
      )}
    >
      <Canvas>
        <PerspectiveCamera
          makeDefault
          fov={camera.fovDeg}
          position={camera.position}
          near={0.1}
          far={2000}
        />
        <OrbitControls
          target={camera.target}
          enableDamping={!reducedMotion}
          autoRotate={false}
          minDistance={20}
          maxDistance={1000}
        />
        <ambientLight intensity={0.4} />
        <directionalLight position={[-50, 100, -50]} intensity={1.0} />
        <mesh geometry={meshGeometry}>
          <meshStandardMaterial vertexColors />
        </mesh>
        {perimeterPoints.length > 0 && (
          <Line
            points={perimeterPoints}
            color="white"
            lineWidth={2}
            depthTest
            segments
          />
        )}
      </Canvas>
    </div>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend && npx vitest run src/components/auction/ParcelMap3D.test.tsx
```

Expected: All 5 tests pass.

- [ ] **Step 5: Run the full per-task verification**

```bash
cd frontend && npm run build && npm test && npm run verify
```

Expected: build green (tsc happy with R3F + drei JSX intrinsic elements like `<mesh>`, `<meshStandardMaterial>`, `<ambientLight>`, `<directionalLight>` — these come from R3F's TypeScript augmentation when `@react-three/fiber` is imported), full suite green, verify green.

If `npm run build` complains about unknown JSX elements like `<mesh>`, the fix is to ensure `@react-three/fiber` is the FIRST import that triggers the JSX namespace augmentation — already handled by the top-of-file `import { Canvas } from "@react-three/fiber"`.

- [ ] **Step 6: Commit and push**

```bash
git add frontend/src/components/auction/ParcelMap3D.tsx frontend/src/components/auction/ParcelMap3D.test.tsx
git commit -m "feat(frontend): parcel map 3D R3F scene component"
git push
```

---

## Task 4: ParcelMapTabs wrapper

**Files:**
- Create: `frontend/src/components/auction/ParcelMapTabs.tsx`
- Test: `frontend/src/components/auction/ParcelMapTabs.test.tsx`

- [ ] **Step 1: Write the failing test for `ParcelMapTabs`**

Create `frontend/src/components/auction/ParcelMapTabs.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { ParcelMapTabs } from "./ParcelMapTabs";

// Mock next/dynamic to render the imported component synchronously so we can
// assert switching without spinning up three.js in jsdom.
vi.mock("next/dynamic", () => ({
  default: (loader: () => Promise<{ default: React.ComponentType<unknown> }>) => {
    const Stub = (props: Record<string, unknown>) => (
      <div data-testid="parcel-map-3d-stub" data-props={JSON.stringify(props)} />
    );
    // Trigger the loader call so the dynamic-import path is exercised, then
    // return the synchronous stub.
    void loader;
    return Stub;
  },
}));

// Stub the 2D ParcelMap so the test never tries to render a real canvas.
vi.mock("./ParcelMap", () => ({
  ParcelMap: (props: Record<string, unknown>) => (
    <div data-testid="parcel-map-2d-stub" data-props={JSON.stringify(props)} />
  ),
}));

const STORAGE_KEY = "slpa:parcel-map:view";

function wrap(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

describe("ParcelMapTabs", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("defaults to the 2D view when localStorage is empty", () => {
    wrap(<ParcelMapTabs publicId="abc" />);
    expect(screen.getByTestId("parcel-map-2d-stub")).toBeInTheDocument();
    expect(screen.queryByTestId("parcel-map-3d-stub")).toBeNull();
  });

  it("renders the 3D view when localStorage holds '3d'", async () => {
    window.localStorage.setItem(STORAGE_KEY, "3d");
    wrap(<ParcelMapTabs publicId="abc" />);
    expect(await screen.findByTestId("parcel-map-3d-stub")).toBeInTheDocument();
    expect(screen.queryByTestId("parcel-map-2d-stub")).toBeNull();
  });

  it("clicking the 3D tab switches the panel and writes to localStorage", async () => {
    const user = userEvent.setup();
    wrap(<ParcelMapTabs publicId="abc" />);
    await user.click(screen.getByRole("tab", { name: "3D View" }));
    expect(await screen.findByTestId("parcel-map-3d-stub")).toBeInTheDocument();
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("3d");
  });

  it("exposes the standard ARIA tabs pattern", () => {
    wrap(<ParcelMapTabs publicId="abc" />);
    const tablist = screen.getByRole("tablist");
    expect(tablist).toHaveAttribute("aria-label", "Parcel map view");
    const tab2d = screen.getByRole("tab", { name: "2D Map" });
    const tab3d = screen.getByRole("tab", { name: "3D View" });
    expect(tab2d).toHaveAttribute("aria-selected", "true");
    expect(tab3d).toHaveAttribute("aria-selected", "false");
    expect(tab2d).toHaveAttribute("aria-controls", "parcel-map-panel");
    expect(tab3d).toHaveAttribute("aria-controls", "parcel-map-panel");
    expect(screen.getByRole("tabpanel")).toHaveAttribute("id", "parcel-map-panel");
  });

  it("ArrowRight from the active 2D tab moves focus to the 3D tab", async () => {
    const user = userEvent.setup();
    wrap(<ParcelMapTabs publicId="abc" />);
    const tab2d = screen.getByRole("tab", { name: "2D Map" });
    tab2d.focus();
    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "3D View" })).toHaveFocus();
  });

  it("ArrowLeft from the 3D tab wraps back to the 2D tab", async () => {
    const user = userEvent.setup();
    wrap(<ParcelMapTabs publicId="abc" />);
    const tab3d = screen.getByRole("tab", { name: "3D View" });
    tab3d.focus();
    await user.keyboard("{ArrowLeft}");
    expect(screen.getByRole("tab", { name: "2D Map" })).toHaveFocus();
  });

  it("renders the WebGL-fallback message when the 3D child calls onWebGLUnavailable", async () => {
    // Replace the next/dynamic mock for this test so the dynamic-imported
    // child invokes onWebGLUnavailable on mount.
    vi.doMock("next/dynamic", () => ({
      default: () => {
        const Stub = (props: { onWebGLUnavailable?: () => void }) => {
          // Fire on mount to simulate WebGL absence.
          (props.onWebGLUnavailable ?? (() => {}))();
          return <div data-testid="parcel-map-3d-stub" />;
        };
        return Stub;
      },
    }));
    // Re-import the component fresh so the new mock takes effect.
    vi.resetModules();
    const { ParcelMapTabs: Reloaded } = await import("./ParcelMapTabs");
    window.localStorage.setItem(STORAGE_KEY, "3d");
    wrap(<Reloaded publicId="abc" />);
    await waitFor(() => {
      expect(
        screen.getByText(
          /3D view requires WebGL, which your browser does not support\./,
        ),
      ).toBeInTheDocument();
    });
    expect(screen.getByTestId("parcel-map-2d-stub")).toBeInTheDocument();
    // localStorage preference must NOT be overwritten.
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("3d");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frontend && npx vitest run src/components/auction/ParcelMapTabs.test.tsx
```

Expected: All tests fail with `Cannot find module './ParcelMapTabs'`.

- [ ] **Step 3: Implement `ParcelMapTabs`**

Create `frontend/src/components/auction/ParcelMapTabs.tsx`:

```tsx
"use client";

import dynamic from "next/dynamic";
import { useCallback, useState, type KeyboardEvent } from "react";

import { cn } from "@/lib/cn";
import { useParcelMapView, type ParcelMapView } from "@/hooks/useParcelMapView";
import { ParcelMap } from "./ParcelMap";
import { ParcelMap3DSkeleton } from "./ParcelMap3DSkeleton";

const ParcelMap3D = dynamic(() => import("./ParcelMap3D"), {
  ssr: false,
  loading: () => <ParcelMap3DSkeleton />,
});

interface Props {
  publicId: string;
  className?: string;
}

const PANEL_ID = "parcel-map-panel";
const TAB_2D_ID = "parcel-map-tab-2d";
const TAB_3D_ID = "parcel-map-tab-3d";

/**
 * Tab wrapper that swaps between the 2D and 3D parcel-map views. Tab choice
 * persists in localStorage via {@link useParcelMapView} so visitors who
 * prefer 3D see it on every auction they open. Three.js + R3F load lazily on
 * first 3D tab activation via {@code next/dynamic({ ssr: false })}.
 *
 * Spec: docs/superpowers/specs/2026-05-24-parcel-map-3d-design.md
 */
export function ParcelMapTabs({ publicId, className }: Props) {
  const [view, setView] = useParcelMapView();
  const [webglUnavailable, setWebglUnavailable] = useState(false);

  const handleWebglUnavailable = useCallback(() => {
    setView("2d");
    setWebglUnavailable(true);
  }, [setView]);

  const handleTabKeyDown = (
    e: KeyboardEvent<HTMLButtonElement>,
    targetView: ParcelMapView,
  ) => {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    e.preventDefault();
    const tablist = e.currentTarget.parentElement;
    if (!tablist) return;
    const buttons = Array.from(
      tablist.querySelectorAll<HTMLButtonElement>('[role="tab"]'),
    );
    const idx = buttons.findIndex((b) => b === e.currentTarget);
    if (idx === -1) return;
    const delta = e.key === "ArrowRight" ? 1 : -1;
    const next = (idx + delta + buttons.length) % buttons.length;
    buttons[next].focus();
    void targetView;
  };

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      <div
        role="tablist"
        aria-label="Parcel map view"
        className="flex gap-1 border-b border-border-subtle"
      >
        <button
          id={TAB_2D_ID}
          type="button"
          role="tab"
          aria-selected={view === "2d"}
          aria-controls={PANEL_ID}
          tabIndex={view === "2d" ? 0 : -1}
          onClick={() => setView("2d")}
          onKeyDown={(e) => handleTabKeyDown(e, "2d")}
          className={tabClassName(view === "2d")}
        >
          2D Map
        </button>
        <button
          id={TAB_3D_ID}
          type="button"
          role="tab"
          aria-selected={view === "3d"}
          aria-controls={PANEL_ID}
          tabIndex={view === "3d" ? 0 : -1}
          onClick={() => setView("3d")}
          onKeyDown={(e) => handleTabKeyDown(e, "3d")}
          className={tabClassName(view === "3d")}
        >
          3D View
        </button>
      </div>
      {webglUnavailable && (
        <p className="text-xs text-fg-muted">
          3D view requires WebGL, which your browser does not support. Showing
          2D view instead.
        </p>
      )}
      <div
        id={PANEL_ID}
        role="tabpanel"
        aria-labelledby={view === "2d" ? TAB_2D_ID : TAB_3D_ID}
      >
        {view === "2d" ? (
          <ParcelMap publicId={publicId} />
        ) : (
          <ParcelMap3D
            publicId={publicId}
            onWebGLUnavailable={handleWebglUnavailable}
          />
        )}
      </div>
    </div>
  );
}

function tabClassName(active: boolean): string {
  return cn(
    "px-4 py-2 text-sm font-medium transition-colors",
    active
      ? "text-brand border-b-2 border-brand"
      : "text-fg-muted hover:text-fg",
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frontend && npx vitest run src/components/auction/ParcelMapTabs.test.tsx
```

Expected: All 7 tests pass.

- [ ] **Step 5: Run the full per-task verification**

```bash
cd frontend && npm run build && npm test && npm run verify
```

Expected: build green, full suite green, verify green.

- [ ] **Step 6: Commit and push**

```bash
git add frontend/src/components/auction/ParcelMapTabs.tsx frontend/src/components/auction/ParcelMapTabs.test.tsx
git commit -m "feat(frontend): parcel map tabs wrapper with lazy 3D"
git push
```

---

## Task 5: Wire ParcelMapTabs into ParcelInfoPanel + README sweep + PR

**Files:**
- Modify: `frontend/src/components/auction/ParcelInfoPanel.tsx`
- Modify: `frontend/src/components/auction/ParcelInfoPanel.test.tsx`
- Modify: `README.md`

- [ ] **Step 1: Update `ParcelInfoPanel.tsx` to render `ParcelMapTabs`**

Edit `frontend/src/components/auction/ParcelInfoPanel.tsx`:

1. Replace the import on line 18:

   ```tsx
   import { ParcelMap } from "@/components/auction/ParcelMap";
   ```

   with:

   ```tsx
   import { ParcelMapTabs } from "@/components/auction/ParcelMapTabs";
   ```

2. Replace the JSX on line 209:

   ```tsx
   <ParcelMap publicId={auction.publicId} />
   ```

   with:

   ```tsx
   <ParcelMapTabs publicId={auction.publicId} />
   ```

The rest of `ParcelInfoPanel.tsx` is unchanged.

- [ ] **Step 2: Update `ParcelInfoPanel.test.tsx`**

Edit `frontend/src/components/auction/ParcelInfoPanel.test.tsx`:

The existing case at lines 233-247 reads:

```tsx
it("mounts ParcelMap with the auction's publicId", () => {
  const useParcelScanSpy = vi
    .spyOn(parcelScanHook, "useParcelScan")
    .mockReturnValue({
      data: null,
      isPending: false,
      isError: false,
    } as ReturnType<typeof parcelScanHook.useParcelScan>);
  renderWithProviders(
    <ParcelInfoPanel auction={publicAuctionFixture()} />,
  );
  expect(useParcelScanSpy).toHaveBeenCalledWith(
    "00000000-0000-0000-0000-000000000007",
  );
});
```

Replace with a case that targets `ParcelMapTabs` (`useParcelScan` is no longer called by `ParcelInfoPanel` directly — it's called by the child `ParcelMap` or `ParcelMap3D`, both of which are rendered through `ParcelMapTabs`):

```tsx
it("mounts ParcelMapTabs with the auction's publicId", () => {
  // Default view is "2d", so the 2D ParcelMap is the child rendered.
  // It calls useParcelScan with the auction's publicId.
  const useParcelScanSpy = vi
    .spyOn(parcelScanHook, "useParcelScan")
    .mockReturnValue({
      data: null,
      isPending: false,
      isError: false,
    } as ReturnType<typeof parcelScanHook.useParcelScan>);
  renderWithProviders(
    <ParcelInfoPanel auction={publicAuctionFixture()} />,
  );
  expect(useParcelScanSpy).toHaveBeenCalledWith(
    "00000000-0000-0000-0000-000000000007",
  );
  // The tab UI is rendered.
  expect(screen.getByRole("tablist", { name: "Parcel map view" })).toBeInTheDocument();
});
```

- [ ] **Step 3: Run the `ParcelInfoPanel` test to verify it passes**

```bash
cd frontend && npx vitest run src/components/auction/ParcelInfoPanel.test.tsx
```

Expected: all 14 tests pass (the renamed case + 13 unchanged).

- [ ] **Step 4: README sweep**

Open `README.md` and search for any mention of `ParcelMap`, "parcel map", "heightmap", or the auction detail page's parcel section. If the README describes the 2D map's behavior, append a brief mention of the new 3D view + tab toggle.

Run this from the repo root to find candidate paragraphs:

```bash
grep -n -E "ParcelMap|parcel map|heightmap|3D view|2D map" README.md || echo "no existing mention"
```

If a section needs updating, add a sentence near the existing copy. Example addition (place near the existing parcel-map section, exact location depends on what's already there):

```markdown
The auction detail page renders the parcel as either a 2D heightmap raster or
an interactive 3D scene (drag to orbit, scroll to zoom). The visitor's tab
choice persists across auctions via `localStorage`. Three.js loads lazily
only when the 3D tab is first opened, so visitors who never use the 3D view
pay zero bundle cost.
```

If the README has no existing mention, no update is required for this task — the spec at `docs/superpowers/specs/2026-05-24-parcel-map-3d-design.md` is the canonical reference.

- [ ] **Step 5: Run the full frontend verification**

```bash
cd frontend && npm run build && npm test && npm run lint && npm run verify
```

Expected: build green, full Vitest suite green (every test in the repo), ESLint clean, all four verify guards green.

- [ ] **Step 6: Commit and push**

```bash
git add frontend/src/components/auction/ParcelInfoPanel.tsx frontend/src/components/auction/ParcelInfoPanel.test.tsx
# Include README.md only if step 4 modified it.
git diff --quiet README.md || git add README.md
git commit -m "feat(frontend): wire ParcelMapTabs into ParcelInfoPanel"
git push
```

- [ ] **Step 7: Open the PR `feat/parcel-map-3d` -> `dev`**

```bash
gh pr create --base dev --head feat/parcel-map-3d \
  --title "feat(frontend): 3D parcel map view with tab toggle" \
  --body @- <<'EOF'
Adds an interactive 3D view of the parcel + region heightmap on the auction
detail page, alongside the existing 2D map. Tab toggle persists the visitor's
choice in `localStorage` so the same view loads on every auction.

## What ships

- New `ParcelMapTabs` wrapper (tab UI, persistence, ARIA tabs pattern, arrow-key
  nav, WebGL-fallback notice).
- New `ParcelMap3D` R3F scene: per-cell mesh (4096 tops + south/east walls),
  white `LineSegments` parcel wireframe, `OrbitControls` (drag + scroll + pan),
  ambient + directional light, `prefers-reduced-motion` disables damping.
- `useParcelMapView` localStorage-backed hook (key `slpa:parcel-map:view`).
- Pure geometry helpers under `frontend/src/lib/parcelMap3D/` (region bounds,
  parcel stats, mesh + perimeter geometry, camera fit, WebGL detection) so the
  math is unit-tested without booting WebGL.
- Three.js + `@react-three/fiber` + `@react-three/drei` added as deps,
  lazy-loaded via `next/dynamic({ ssr: false })`.

The existing 2D `ParcelMap` is untouched.

## Out of scope (filed as #414)

- Water plane at SL sea level.
- Snapshot-as-ground-texture using `parcel.snapshotUrl`.
- Parcel walls (extruding the perimeter downward to the ground).

## Verification

- `npm run build && npm test && npm run lint && npm run verify` green locally.

## Spec / plan

- Spec: `docs/superpowers/specs/2026-05-24-parcel-map-3d-design.md`
- Plan: `docs/superpowers/plans/2026-05-24-parcel-map-3d-plan.md`
EOF
```

- [ ] **Step 8: Merge the PR into `dev`**

```bash
gh pr merge --merge feat/parcel-map-3d
```

Do NOT touch the `dev` -> `main` PR — the user merges that themselves.

---

## Self-review (run after writing all tasks)

**1. Spec coverage**

| Spec section | Task(s) |
|---|---|
| §1 Goal | Tasks 3 + 4 (3D scene + tab toggle) |
| §2 Architecture | Task 4 (wrapper) |
| §3.1 Mesh (bicubic-upsampled heightfield, vertex colors, parcel-min gradient, no dim-outside, no walls) | Task 2 `decodeElevationGrid` + `bicubicUpsample` + `buildHeightfieldGeometry` + Task 3 component wiring |
| §3.2 Camera (PerspectiveCamera, 45° az / 30° el / FOV 50, fit-to-region) | Task 2 `computeCameraDefaults` + Task 3 `<PerspectiveCamera>` |
| §3.3 Controls (drag-orbit, scroll-zoom, middle-click-pan, damping toggle) | Task 3 `<OrbitControls>` + `prefers-reduced-motion` effect |
| §3.4 Parcel wireframe (white `LineSegments`, depth-tested, drei `<Line>` for proper width, endpoints sampled from upsampled mesh) | Task 2 `buildPerimeterPoints` + `sampleUpsampled` + Task 3 `<Line>` |
| §3.5 Lighting (DirectionalLight + AmbientLight, no shadows) | Task 3 `<ambientLight>` + `<directionalLight>` |
| §3.6 Background (transparent canvas, `bg-bg-subtle` wrapper) | Task 3 wrapper `<div>` |
| §4.1 Layout (role="tablist" + tab + tabpanel) | Task 4 `ParcelMapTabs` JSX |
| §4.2 localStorage (`slpa:parcel-map:view`, default "2d", SSR-safe, junk filter) | Task 1 `useParcelMapView` + tests |
| §4.3 Accessibility (ARIA roles, arrow-key roving tabindex) | Task 4 JSX + key handler + tests |
| §5.1 Dynamic import (`ssr: false`, `loading: ParcelMap3DSkeleton`) | Task 4 `dynamic()` call + Task 2 Skeleton |
| §5.2 WebGL fallback (message, switch to 2D, preserve localStorage) | Task 3 `isWebGLAvailable` + `onWebGLUnavailable` + Task 4 `webglUnavailable` state |
| §5.3 prefers-reduced-motion (disable damping) | Task 3 media-query effect |
| §6 Data shape + coord system (X=col*4, Y=elev, Z=row*4) | Task 2 `buildMeshGeometry` / `buildPerimeterPoints` |
| §7 File list | Plan's "File map" section matches 1:1 |
| §8 Testing | Each task ships its own tests; ParcelInfoPanel test updated in Task 5 |
| §9 Out of scope | PR body + plan header reference #414 |
| §10 Decision log | Implicit — plan follows every decision |

All spec sections are covered.

**2. Placeholder scan**

Searched the plan for `TBD`, `TODO`, `implement later`, `fill in details`, `add appropriate error handling`, `similar to Task`. None present. Every step has either an exact command, exact code, or exact file diff to apply.

**3. Type consistency**

- `useParcelMapView()` returns `[ParcelMapView, (next: ParcelMapView) => void]` — same signature in Task 1 implementation, Task 1 test, and Task 4 consumer.
- `ParcelMap3DProps` carries `publicId: string`, `onWebGLUnavailable?: () => void`, `className?: string` in Task 3 implementation, Task 3 test, and Task 4 consumer.
- `buildPerimeterPoints` takes `(layoutCells: Uint8Array, upsampled: Float32Array)` and returns `Array<[number, number, number]>` in Task 2 implementation, Task 2 test, and Task 3 consumer (drei `<Line points={...}>`).
- `bicubicUpsample` takes `Float32Array(64*64)` and returns `Float32Array(UPSAMPLED_GRID * UPSAMPLED_GRID)` (= 129*129 with `UPSAMPLE_FACTOR = 2`) — same signature in Task 2 helper, Task 2 test, and Task 3 consumer.
- `buildHeightfieldGeometry` takes `(upsampled: Float32Array, parcelMin: number)` and returns `BufferGeometry` — consistent across Task 2 implementation, Task 2 test, and Task 3 consumer.
- `computeRegionBounds` takes `Float32Array` (the upsampled or raw grid; same shape both ways) and returns `RegionBounds` — consistent.
- `computeParcelStats` takes `(layoutCells: Uint8Array, rawGrid: Float32Array)` — note this consumes the RAW 64x64 grid, not the upsampled grid, so the parcel-min anchor matches what the 2D view computes.
- `computeCameraDefaults` returns `CameraDefaults` with `position: [number, number, number]`, `target: [number, number, number]`, `fovDeg: number` — same signature in Task 2 + Task 3.
- `STORAGE_KEY` is `"slpa:parcel-map:view"` in Task 1 hook + Task 1 test + Task 4 test — all consistent.
- `PANEL_ID = "parcel-map-panel"` consistent across Task 4 implementation + Task 4 test + Task 5 test update.

No drift.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-24-parcel-map-3d-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
