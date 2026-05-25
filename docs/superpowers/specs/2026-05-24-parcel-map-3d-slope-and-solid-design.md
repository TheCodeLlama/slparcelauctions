# Parcel Map 3D View - Slope Mode + Solid Look

**Date:** 2026-05-24
**Status:** Awaiting user review.
**Builds on:** `docs/superpowers/specs/2026-05-24-parcel-map-3d-design.md` (the initial 3D view spec, now shipped).
**Touches:** `docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md` (the shared 2D/3D color helper changes apply to both views).
**Partially addresses:** [Issue #414](https://github.com/TheCodeLlama/slparcelauctions/issues/414) — adds region walls (not the parcel-specific walls originally noted there; those remain a separate enhancement).

## 1. Goal

Two related improvements to the parcel-map 3D view that address feedback from a visitor looking at a real listing:

1. **Slope-shaded color mode.** Today the terrain is colored by elevation above the parcel's lowest point. A near-vertical cliff face sitting near the parcel's low point shows green, hiding the fact that it's unbuildable. Add an alternative color mode that colors by local slope angle (flat = green, 45° vertical = red), and let the visitor toggle between the two modes inline.

2. **Solid look.** The terrain currently renders as a single top sheet, which looks like floating geometry. Add downward-extruded region walls and a bottom plane so the terrain reads as a solid chunk of land.

Cascading change: the 2-stop simplification of the green-red gradient (no yellow midpoint) applies to both the 2D map and the 3D view, since they share `frontend/src/lib/parcelMap/colors.ts`.

## 2. Architecture

```
ParcelMap3D (existing, "use client", default export)
  uses:
    useParcelScan(publicId)          (existing)
    useParcelMapColorMode()          (NEW; localStorage-backed)
  renders:
    <Canvas>
      <PerspectiveCamera ...>
      <OrbitControls ...>
      <ambientLight />
      <directionalLight />
      <mesh geometry={meshGeometry}>   <-- now includes top + walls + floor
        <meshStandardMaterial vertexColors />
      </mesh>
      {perimeterPoints.length > 0 && (
        <Line color="white" lineWidth={2} ... />    (existing parcel wireframe)
      )}
    </Canvas>
    <ParcelMap3DColorModeToggle .../>  <-- NEW, top-right absolute, ARIA radio group
```

Backend is untouched. No new API calls. The same `useParcelScan` payload feeds both color modes.

## 3. Color mode toggle (UI + persistence)

### 3.1 Placement

An inline button group floats top-right inside the 3D canvas wrapping `<div>`. Two buttons: `[Elevation] [Slope]`. The button group is hidden during the loading skeleton state and shown once scan data is available.

```
+-- 3D canvas wrapper ---------------------------+
|                                  [Elev][Slp]   |
|                                                |
|     [orbitable 3D terrain]                     |
|                                                |
+------------------------------------------------+
```

### 3.2 ARIA pattern

`role="radiogroup"` with `aria-label="Color by"`. Two child buttons with `role="radio"` and `aria-checked={true|false}`. This is NOT a tabs pattern — the user is choosing how to color the same scene, not which scene to render, so the tabs pattern (which requires a separate `role="tabpanel"` per option) is wrong. The radio-group pattern correctly models "pick one of N for an attribute of the current view."

Keyboard nav: standard radio-group behavior — Tab focuses the group, Arrow keys cycle the selected option. Selecting via Arrow keys is enough; no separate Enter/Space activation needed (unlike tabs).

### 3.3 Persistence

- **Hook:** new `useParcelMapColorMode` in `frontend/src/hooks/useParcelMapColorMode.ts`. Mirrors `useParcelMapView` exactly.
- **Storage key:** `slpa:parcel-map:3d-color`
- **Values:** `"elevation" | "slope"`
- **Default:** `"elevation"`
- **Two-phase mount:** initial render returns the default on both server and client; `useEffect` reads the stored value on mount. Avoids hydration mismatch (the same lesson the original 3D PR learned the hard way; see `useParcelMapView.ts`'s JSDoc).
- Junk values fall back to default.
- localStorage errors are swallowed silently.
- The setter writes through on every change.

### 3.4 Visibility

The toggle is mounted inside `ParcelMap3D`'s success-path render branch (the same branch that renders the `<Canvas>`). It's not rendered during:

- The pending/skeleton state (no toggle to act on when there's no terrain).
- The WebGL-fallback state (no 3D scene at all in that branch).
- The `data === null` state (no scan available).

## 4. Color mapping (both modes)

### 4.1 Shared 2-stop helper

`frontend/src/lib/parcelMap/colors.ts` swaps its 3-stop gradient for a 2-stop `lerp(green, red, t)` builder. Both `gradientColor` (existing, elevation mode) and `slopeColor` (new, slope mode) use it. `MAP_COLORS.yellow` is removed (it was only referenced by the now-deleted 3-stop logic).

### 4.2 Elevation mode

`gradientColor(deltaMeters)` becomes:

```ts
export function gradientColor(deltaMeters: number): Rgb {
  const t = Math.min(1, Math.max(0, deltaMeters / 8));
  return lerp(MAP_COLORS.green, MAP_COLORS.red, t);
}
```

- `delta ≤ 0` -> green (parcel low point or below it)
- `delta ≥ 8m` -> red (saturates; the "un-flattenable spread" SL terraforming limit)
- In between: smooth lerp through olive/khaki tones (e.g. `delta = 4m` -> `rgb(~137, ~133, ~81)`)

The visible result on the 2D map and the 3D view: same overall meaning (green = low, red = high) but no hard horizontal band at the 4m yellow midpoint.

### 4.3 Slope mode

```ts
export function slopeColor(slopeRad: number): Rgb {
  const t = Math.min(1, Math.max(0, slopeRad / (Math.PI / 4)));
  return lerp(MAP_COLORS.green, MAP_COLORS.red, t);
}
```

- `0` rad (flat) -> green
- `π/4` rad (45°) -> red (saturates)
- Steeper still reads red; the gradient doesn't waste range on theoretical 45-90° terrain that almost never occurs in SL

45° is the practical "unbuildable" threshold in SL (houses and roads stop working comfortably above this). The user-meaningful cliff/walkable distinction lands cleanly in the gradient's bright transition zone.

### 4.4 Slope-value computation

Slope values come from a closed-form finite difference on the upsampled height grid, NOT from `computeVertexNormals` output. For each upsampled vertex `(uRow, uCol)`:

```
dhdx = (h[uRow,   uCol+1] - h[uRow,   uCol-1]) / (2 * UPSAMPLED_SPACING_M)
dhdz = (h[uRow+1, uCol  ] - h[uRow-1, uCol  ]) / (2 * UPSAMPLED_SPACING_M)
slopeRad = atan(sqrt(dhdx*dhdx + dhdz*dhdz))
```

Edges use one-sided differences (clamp-to-edge). The result is a 129x129 `Float32Array` of slope angles in radians, same indexing as the upsampled height grid.

**Why finite-difference, not normals?** It's deterministic and easy to unit-test in isolation. It also decouples slope computation from the BufferGeometry lifecycle — we can compute slopes before building geometry, memoize them, and feed both top and wall coloring without juggling normal attributes.

**New pure helper:** `computeSlopeGrid(upsampled: Float32Array): Float32Array` in `frontend/src/lib/parcelMap3D/geometry.ts`.

### 4.5 2D map cascade

`frontend/src/components/auction/ParcelMap.tsx` consumes `gradientColor` via the canvas paint pass and via the `ParcelMapLegend` subcomponent's CSS gradient. The cascading changes:

- The canvas paint pass produces smooth green-to-red tones, no yellow band.
- `ParcelMapLegend`'s CSS gradient becomes two stops (green at 0%, red at 100%) instead of three.
- The `+4 m` midpoint label is removed from the legend's axis labels.
- The legend explainer text remains — it still mentions the SL 4m terraforming raise/lower limit for context, just without a dedicated visual marker.

The 2D map's dim-outside treatment, hover tooltip, focus cursor, and SW-first row flip are all unchanged.

## 5. Solid mesh (walls + floor)

### 5.1 Floor depth

`floorY = regionMin - 8`, where `regionMin` comes from `computeRegionBounds(upsampled)` (the upsampled grid's min, not the raw grid). Eight meters below the lowest cell means a flat region still presents as a visible slab and a hilly region presents as a tall block, with predictable visual mass across listings.

### 5.2 Walls

Four perimeter walls, one per region edge:

- **North wall** at `z = 256` (uRow = 128 row of upsampled grid)
- **South wall** at `z = 0` (uRow = 0)
- **East wall** at `x = 256` (uCol = 128)
- **West wall** at `x = 0` (uCol = 0)

Each wall is a strip of 128 quads, one between every pair of adjacent upsampled perimeter vertices. Top edge sits at the actual perimeter heightfield elevation (so the wall follows the terrain's bumpiness at its top edge — no fake flat band). Bottom edge sits at `floorY`. Geometry is added inline in `buildHeightfieldGeometry`.

**Triangle winding:** each wall's triangles are wound so face normals point OUTWARD from the region center. Concretely:

- North wall: face normal points `+Z`.
- South wall: face normal points `-Z`.
- East wall: face normal points `+X`.
- West wall: face normal points `-X`.

The regression test asserts the average face-normal direction per wall matches its expected outward axis.

**Vertices:** the wall uses its own vertex entries (separate from the top mesh) so the per-wall vertex normals come out clean from `computeVertexNormals` without averaging into the top mesh's normals. The wall is visually flat-shaded along its vertical extent.

**Per-vertex color:** at each perimeter point, the color is `terrainColorAt(uRow, uCol, mode) * 0.55`. Both top and bottom edge of the wall at that perimeter point share the same color, so the wall reads as a uniformly dimmed strip of the terrain material. When the user flips between elevation and slope mode, the wall recolors in lockstep with the top mesh.

**Wall geometry totals:** 4 sides * 128 quads * 2 tris = 1,024 wall triangles, 4 sides * 2 (top + bottom) * 129 (vertices per perimeter row) = 1,032 wall vertices.

### 5.3 Floor

A single quad spanning the full region at `floorY`:

- Vertices: `(0, floorY, 0)`, `(256, floorY, 0)`, `(0, floorY, 256)`, `(256, floorY, 256)`.
- **Triangle winding:** CCW when viewed from below (`-Y` looking `+Y`), so the face normal points `-Y` (downward). The lit side faces down; only visible if the visitor orbits below the region (rare but supported by drei's OrbitControls).
- **Color:** a fixed earth tone `rgb(60, 50, 40)`. The floor is rarely visible; this is defensive coverage for the low-orbit case.
- 2 triangles, 4 vertices.

### 5.4 Total geometry overhead

Adds 1,026 triangles and 1,036 vertices to the existing ~33k-tri heightfield. Negligible on any modern GPU.

## 6. Files

**New:**

| File | Responsibility |
|---|---|
| `frontend/src/hooks/useParcelMapColorMode.ts` | localStorage-backed color-mode state, two-phase mount. |
| `frontend/src/hooks/useParcelMapColorMode.test.tsx` | Default, persistence, junk filter, SSR safety. |
| `frontend/src/components/auction/ParcelMap3DColorModeToggle.tsx` | Inline button group, ARIA radio group, pure presentational. |
| `frontend/src/components/auction/ParcelMap3DColorModeToggle.test.tsx` | Radio-group ARIA, click handling, keyboard nav. |

**Modified:**

| File | Change |
|---|---|
| `frontend/src/lib/parcelMap/colors.ts` | 3-stop -> 2-stop `gradientColor`; new `slopeColor`; drop `MAP_COLORS.yellow`. |
| `frontend/src/lib/parcelMap/colors.test.ts` | Update midpoint expectations; add `slopeColor` cases. |
| `frontend/src/lib/parcelMap3D/geometry.ts` | New `computeSlopeGrid`; extend `buildHeightfieldGeometry(upsampled, parcelMin, floorY, mode)`; emit walls + floor. |
| `frontend/src/lib/parcelMap3D/geometry.test.ts` | New `computeSlopeGrid` tests; assert wall/floor vertex + index counts; assert wall outward normals; assert floor downward normal; assert mode swap produces different colors at the same vertex. |
| `frontend/src/components/auction/ParcelMap.tsx` | `ParcelMapLegend` becomes 2-stop CSS gradient; drop `+4 m` mid label. |
| `frontend/src/components/auction/ParcelMap3D.tsx` | Call `useParcelMapColorMode`; thread `colorMode` + `floorY` into `buildHeightfieldGeometry` memo; render `<ParcelMap3DColorModeToggle>` inside the scene wrapper. |
| `frontend/src/components/auction/ParcelMap3D.test.tsx` | New case: toggling color mode rebuilds geometry / passes the new mode to the helper. |

**Untouched:**

- `frontend/src/components/auction/ParcelMapTabs.tsx` (the 2D/3D tab wrapper, separate concern).
- `frontend/src/components/auction/ParcelMap3DSkeleton.tsx`.
- `frontend/src/hooks/useParcelMapView.ts`.
- All backend code.

## 7. Testing

### 7.1 colors

- `gradientColor(0)` -> exact green.
- `gradientColor(8)` -> exact red.
- `gradientColor(4)` -> midpoint olive `rgb(~137, ~133, ~81)`.
- `gradientColor(-1)` -> clamps to green.
- `gradientColor(20)` -> clamps to red.
- `slopeColor(0)` -> exact green.
- `slopeColor(Math.PI / 4)` -> exact red.
- `slopeColor(Math.PI / 8)` -> midpoint olive.
- `slopeColor(Math.PI / 2)` -> clamps to red.

### 7.2 computeSlopeGrid

- Flat input (all elevations equal) -> all zeros.
- Linear ramp in X (dh/dx = constant, dh/dz = 0) -> uniform `atan(dh/dx)` everywhere except a small tolerance at the edges from the one-sided difference.
- Returns a `Float32Array` of length `UPSAMPLED_GRID * UPSAMPLED_GRID`.
- Edge cells (uRow=0, uRow=128, uCol=0, uCol=128) use clamp-to-edge one-sided differences (no NaN, no out-of-bounds reads).

### 7.3 buildHeightfieldGeometry (extended)

Existing tests stay; the signature gains `floorY` and `mode` (`elevation` | `slope`):

- Total vertex count = `UPSAMPLED_GRID^2 + 4 * 2 * UPSAMPLED_GRID + 4 = 17,677`. Total index count = `(UPSAMPLED_GRID - 1)^2 * 6 + 4 * (UPSAMPLED_GRID - 1) * 6 + 6 = 101,382`.
- `mode = "elevation"` with flat input -> vertex 0 is green (existing assertion still holds; the 2-stop midpoint change doesn't move the green endpoint).
- `mode = "slope"` with flat input -> vertex 0 is also green (zero slope).
- Wall vertex at perimeter vertex (0, 0): both top and bottom share the same color, which equals the top mesh's vertex (0, 0) color * 0.55 (use `toBeCloseTo` on each rgb channel).
- Wall outward normals: assert the average face-normal direction per wall is approximately the expected outward axis.
- Floor face normal: assert the average is `(0, -1, 0)`.

### 7.4 useParcelMapColorMode

Mirrors `useParcelMapView.test.tsx` structure:

- Returns `"elevation"` when localStorage is empty.
- Returns `"slope"` when localStorage has `"slope"`.
- Ignores junk -> falls back to `"elevation"`.
- `setMode` writes to localStorage and updates returned state.
- Two-phase mount: server-side `useState` initializer returns default; client `useEffect` reads stored value.

### 7.5 ParcelMap3DColorModeToggle

- Renders `role="radiogroup"` with `aria-label="Color by"`.
- Both buttons have `role="radio"` and correct `aria-checked` for the current `mode` prop.
- Clicking either button fires `onChange` with the right value.
- Arrow-key navigation cycles `aria-checked` between the two options.

### 7.6 ParcelMap3D

Existing tests stay. Add:

- When `colorMode` is `"slope"`, `buildHeightfieldGeometry` is called with `mode = "slope"` (mock the helper or assert via a wrapping spy).
- Toggling the radio group via the rendered `<ParcelMap3DColorModeToggle>` triggers a re-render with the new mode.

### 7.7 Manual

- Switch between elevation and slope on a real parcel that has a visible cliff vs flat top. Slope mode should color the cliff red and the flat top green; elevation mode does the opposite for the same geometry.
- Orbit below the region. The floor should be visible (lit) and the wall undersides should not show through.
- Refresh between auctions: the saved color mode persists.

## 8. Out of scope

Tracked in [Issue #414](https://github.com/TheCodeLlama/slparcelauctions/issues/414):

- **Water plane** at SL sea level (Y=20).
- **Snapshot-as-ground-texture** using `parcel.snapshotUrl`.
- **Parcel-specific walls** (extruding just the listed parcel's perimeter to the ground). This spec adds REGION walls, which addresses the "floating geometry" concern; parcel-specific walls remain a separate enhancement on the issue.

Also explicitly not in this spec:

- Saving the color mode per-auction or per-user (lives in localStorage; same shape as the 2D/3D tab choice).
- Hover/click slope readout in the 3D view (still no per-vertex tooltip in 3D; the 2D view continues to own per-cell readouts).
- Custom slope thresholds beyond the fixed 0-45° mapping.
- Animated transitions between color modes.

## 9. Decision log (2026-05-24)

- **Toggle UI: inline button group.** Picked over extending the existing 2D/3D tab row because the slope/elevation choice is an attribute of the 3D view, not a separate view. Picked over a dropdown because two options don't need a select widget and the inline buttons are always visible during interaction.
- **Slope scale: 0-45°.** Picked over 0-90° because most SL terrain falls in 0-45° and the 45° threshold is the practical "unbuildable" line. Picked over auto-scale because cross-parcel comparability matters.
- **Wall coloring: darker variant of terrain * 0.55.** Picked over solid earth tone because the gradient continuity across top and walls reinforces "this is a chunk of the same material" without making real cliffs visually identical to the region cut edge.
- **Floor depth: regionMin - 8m.** Picked over auto-proportional and sea-level because predictable visual mass across listings beats sculpted-but-inconsistent.
- **Slope from finite difference, not normals.** Picked over reading `computeVertexNormals` output because finite difference is deterministic, easy to unit-test, and decouples slope computation from the BufferGeometry lifecycle.
- **2-stop gradient (no yellow).** Applied to both 2D and 3D via the shared `colors.ts` helper. Picked over keeping the 3-stop for the 2D map because a single source of truth beats divergent behavior between views and the user explicitly asked for a smoother gradient.
- **Color mode persistence: localStorage, key `slpa:parcel-map:3d-color`.** Same pattern as `slpa:parcel-map:view`. Default `"elevation"`.
- **Region walls, not parcel walls.** The parcel-specific walls noted in issue #414 are a different effect (extruding just the listed parcel's perimeter); this spec addresses the "floating geometry" concern with the broader region-level extrusion. Parcel walls remain on the issue.
