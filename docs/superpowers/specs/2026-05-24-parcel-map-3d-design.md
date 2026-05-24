# Parcel Map 3D View

**Date:** 2026-05-24
**Status:** Awaiting user review.
**Builds on:** Parcel Map (Frontend) spec `docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md` (which ships the 2D parcel-min-anchored heatmap this spec sits alongside).
**Defers to:** [Issue #414](https://github.com/TheCodeLlama/slparcelauctions/issues/414) — water plane, snapshot-as-ground-texture, parcel walls (out of scope here).

## 1. Goal

Add an interactive 3D view of the parcel + region heightmap on the auction detail page, alongside the existing 2D map. Visitors can drag to orbit the camera and scroll to zoom. The parcel is outlined in a white wireframe so it stays visible from any angle and any lighting.

The two views (2D and 3D) live behind a tab toggle in the same slot inside `ParcelInfoPanel`. The tab choice persists across auctions via `localStorage` so a visitor who prefers 3D sees 3D on every listing they open.

Three.js + R3F + drei load lazily (Next.js `dynamic()` with `ssr: false`) only when the 3D tab is first opened, so visitors who never interact with the 3D view pay zero bundle cost.

## 2. Architecture

```
ParcelInfoPanel (server component, existing)
    └── ParcelMapTabs (NEW, "use client")
         ├── tab UI (role="tablist", localStorage persistence, arrow-key nav)
         ├── ParcelMap (EXISTING 2D, unchanged) — rendered when tab === "2d"
         └── ParcelMap3D (NEW, lazy via dynamic())   — rendered when tab === "3d"
                ├── three.js Scene
                ├── PerspectiveCamera + OrbitControls (drei)
                ├── per-cell BufferGeometry (tops + visible walls)
                └── parcel-perimeter LineSegments (white wireframe)
```

Backend is untouched. `useParcelScan` is the existing data source for both views.

## 3. The 3D scene

### 3.1 Mesh

One custom `BufferGeometry` containing 4096 cell-top quads + the visible walls (south-facing and east-facing only; north and west walls are occluded from typical orbit angles and skipping them halves the wall poly count without visible degradation when the camera passes through those orientations). Each cell-top quad is 4×4 m at the cell's elevation. Walls extend down from the cell top to the region's minimum elevation (so all walls share a common floor, which keeps the geometry simple).

Vertex colors per face — same parcel-min-anchored gradient the 2D view uses:
- Top faces: full color from `gradientColor(cellElevation - parcelMin)` (the existing `colors.ts` helper).
- Wall faces: same hue, multiplied by `0.6` (darker, the "shaded side").

Outside-parcel cells render in their full gradient color (NOT dimmed). The 3D view does not use the 2D view's dim-outside treatment — the white parcel wireframe (section 3.4) is the parcel-vs-non-parcel signal in 3D.

Approximate poly count: 4096 tops × 2 tris + 4096 cells × 2 visible walls × 2 tris ≈ 24 k triangles. Negligible for any GPU made in the last 15 years.

### 3.2 Camera

`<PerspectiveCamera>` from R3F. Default position: 45° azimuth, 30° elevation, looking at the region's geometric center at height `(rMin + rMax) / 2`. Field of view 50°. Camera distance auto-sized so the whole region fits comfortably in the viewport at default zoom.

Camera resets to the default position on every mount (no per-auction or per-user persistence in this spec).

### 3.3 Controls

`<OrbitControls>` from `@react-three/drei`:
- **Mouse drag** — orbit (azimuth + elevation).
- **Scroll wheel** — zoom (`minDistance` and `maxDistance` clamped so the camera can neither penetrate a cell nor fly off into empty space).
- **Middle-click drag** — pan.
- **`enableDamping: true`** by default for momentum on drag/zoom; **disabled** when `prefers-reduced-motion: reduce` matches the visitor's media query.
- **`autoRotate: false`** — no drift.

Keyboard controls: drei's OrbitControls doesn't ship keyboard rotation. The 3D view is "look around, not interact with specific cells" — the per-cell aria-live + tooltip lives in the 2D view (which is the default tab). Accessibility responsibility for "what's at this exact spot" stays with 2D.

### 3.4 Parcel boundary — white wireframe

A `LineSegments` mesh whose vertices trace the parcel perimeter at the top of each parcel cell. Edge-detection logic mirrors the 2D cyan-outline pass: for each parcel cell, for each of its 4 edges, if the neighbor on that edge is outside the parcel (or off-grid), emit a line segment in 3D at the top of that cell along that edge.

Material: `LineBasicMaterial({ color: 'white', linewidth: 2, depthTest: true })`. Depth-tested so the wireframe sits on the parcel cells' tops and disappears behind intervening hills — preserves spatial intuition ("the parcel is on the far side of this hill") rather than ghost-overlaying through geometry.

The user's explicit ask for this section: the 2D view's dim-outside treatment is hard to read; a white bounding box around the parcel is always visible.

### 3.5 Lighting

- One `DirectionalLight` from the upper-northwest (`position: (-50, 100, -50)`, looking at origin), intensity 1.0.
- One `AmbientLight` intensity 0.4 for fill.

Enough shading to differentiate lit tops from shaded walls without going PBR. No shadows (real-time shadows on a 24k-tri mesh are cheap, but visually noisy for a small viewer and add complexity).

### 3.6 Background

Transparent R3F canvas (no `<color attach="background" args={[...]} />`). The wrapping `<div>` uses `bg-bg-subtle` so the canvas blends with the page theme without forcing a black render.

## 4. The tab UI

### 4.1 Layout

`ParcelMapTabs` is the new wrapper that replaces the direct `<ParcelMap>` mount in `ParcelInfoPanel`. Renders:

```tsx
<div className="flex flex-col gap-3">
  <div role="tablist" aria-label="Parcel map view" className="flex gap-1">
    <button role="tab" aria-selected={view === "2d"} aria-controls="parcel-map-panel" ...>2D Map</button>
    <button role="tab" aria-selected={view === "3d"} aria-controls="parcel-map-panel" ...>3D View</button>
  </div>
  <div id="parcel-map-panel" role="tabpanel">
    {view === "2d" ? <ParcelMap publicId={...} /> : <ParcelMap3D publicId={...} />}
  </div>
</div>
```

Tab button styling: matches existing tab-like patterns in the app (e.g. dashboard tabs at `frontend/src/components/...`); semantic Tailwind tokens only.

### 4.2 localStorage persistence

- **Key:** `slpa:parcel-map:view`
- **Values:** `"2d"` | `"3d"`
- **Default** (no stored value): `"2d"`.
- **Read** on `ParcelMapTabs` mount (inside a `useEffect`; `localStorage` is browser-only, must not run during SSR).
- **Write** on tab change.
- **Hook:** `useParcelMapView()` returns `[view, setView]` and handles the SSR-safety + read/write internally. New file: `frontend/src/hooks/useParcelMapView.ts`.

### 4.3 Accessibility

- `role="tablist"` on the tab row, `aria-label` describing the widget.
- `role="tab"` on each button, `aria-selected={true|false}`, `aria-controls={panelId}`.
- `role="tabpanel"` on the content div, `aria-labelledby` referencing the active tab's id.
- Arrow-Left / Arrow-Right move focus between tabs. Enter / Space activates. Standard W3C ARIA tabs pattern.
- Tab focus stays inside the tab UI after switching (does NOT jump to the canvas or scene), so keyboard users can flip back-and-forth quickly.

## 5. Lazy load + WebGL fallback

### 5.1 Dynamic import

```tsx
// ParcelMapTabs.tsx
import dynamic from "next/dynamic";

const ParcelMap3D = dynamic(() => import("@/components/auction/ParcelMap3D"), {
  ssr: false,
  loading: () => <ParcelMap3DSkeleton />,
});
```

`ssr: false` is mandatory — three.js needs a WebGL context which doesn't exist in the Node server runtime. The 3D bundle (three.js + @react-three/fiber + @react-three/drei) only ships on the first `view === "3d"` render.

`ParcelMap3DSkeleton` is a 320×320 `animate-pulse bg-bg-subtle` block matching the 2D map's loading state.

### 5.2 WebGL absent / context-lost

R3F detects WebGL availability via the canvas's `getContext("webgl2") ?? getContext("webgl")`. If both return null:

1. Render a one-line message in the tab panel: `"3D view requires WebGL, which your browser does not support. Showing 2D view instead."` (no em-dashes, plain prose).
2. Programmatically switch the active tab back to `"2d"`.
3. Do NOT overwrite the `localStorage` value — the visitor's preference is preserved; they just can't honor it on this device. On a WebGL-capable browser later, the saved preference still applies.

Context-lost mid-session (rare, usually a GPU crash) follows the same path: catch via R3F's `onError`/canvas event, switch to 2D, surface the message.

### 5.3 `prefers-reduced-motion`

When `(prefers-reduced-motion: reduce)` matches, set `enableDamping: false` on `<OrbitControls>` and `autoRotate: false`. Drag still rotates, but momentum/inertia stops with the cursor. Snappy, no drift.

## 6. Data shape + computation

Both views consume the existing `useParcelScan` hook's payload:

```ts
{ gridSize: 64, cellSizeMeters: 4, layoutCellsBase64, heightCellsBase64, baseMeters, stepMeters, scannedAt }
```

The 3D component decodes via the existing `lib/parcelMap/encoding.ts` helpers (`decodeBase64ToBytes`, `isCellInParcel`, `decodeElevationCell`). Per-cell color via `lib/parcelMap/colors.ts` (`gradientColor`, `MAP_COLORS`). No new helpers, no duplication.

Per-cell coordinates in the 3D scene:
- World X = `col * 4` (east-west, 0 = west edge of region).
- World Z = `row * 4` (north-south, 0 = south edge of region, increases northward).
- World Y = `elev(row, col)` (vertical, meters above sea level).

This matches SL's right-handed coordinate convention and keeps "north is +Z" — natural for a visitor looking at a map.

## 7. Files

**New:**

| File | Responsibility |
|---|---|
| `frontend/src/components/auction/ParcelMapTabs.tsx` | Tab UI + localStorage + delegates to 2D or 3D |
| `frontend/src/components/auction/ParcelMapTabs.test.tsx` | Tab switching, localStorage persistence, ARIA roles, arrow-key nav |
| `frontend/src/components/auction/ParcelMap3D.tsx` | R3F scene; default export so `dynamic()` can import |
| `frontend/src/components/auction/ParcelMap3D.test.tsx` | Mock R3F + drei; assert geometry inputs match scan data |
| `frontend/src/components/auction/ParcelMap3DSkeleton.tsx` | Loading placeholder while three.js dynamic-imports |
| `frontend/src/hooks/useParcelMapView.ts` | localStorage-backed tab-state hook |
| `frontend/src/hooks/useParcelMapView.test.tsx` | SSR safety, read on mount, write on change, default value |

**Modified:**

| File | Change |
|---|---|
| `frontend/src/components/auction/ParcelInfoPanel.tsx` | Replace `<ParcelMap ... />` with `<ParcelMapTabs ... />` |
| `frontend/src/components/auction/ParcelInfoPanel.test.tsx` | Update the existing "mounts ParcelMap with the auction's publicId" case to target `ParcelMapTabs` |
| `frontend/package.json` | Add `three`, `@react-three/fiber`, `@react-three/drei` deps |

The existing `ParcelMap.tsx` (2D) is NOT touched.

## 8. Testing

### 8.1 ParcelMapTabs

- Default tab is `"2d"` when localStorage is empty.
- Renders the saved tab when localStorage has a value (`"3d"` -> 3D pane active on mount).
- Clicking the 3D tab writes `"3d"` to localStorage.
- ARIA: `role="tab"`, `aria-selected` toggles, `aria-controls` matches the panel id.
- Arrow-Left / Arrow-Right cycle focus between tabs (not the activation; W3C tabs pattern is roving tabindex with manual activation via Enter/Space).
- The dynamic-imported `ParcelMap3D` is replaced with a stub in tests (Vitest `vi.mock("next/dynamic")` or a manual module mock) so the test doesn't need to load three.js. Asserts the dynamic mock was invoked when the 3D tab activates.

### 8.2 ParcelMap3D

- `@react-three/fiber` and `@react-three/drei` are module-mocked. The component renders into a stub `<canvas>` whose props the test asserts.
- Test data: a small synthetic scan payload with known parcel + known heights. Assert the component:
  - Decodes the layout + height bytes correctly (delegates to existing helpers, so this is mostly a smoke test).
  - Constructs geometry input with the right number of cells (4096) and the right number of parcel-perimeter line segments (compute expected from the test fixture).
  - Passes the right camera defaults.
- No real WebGL render is exercised in CI — the geometry-input assertions are the durable signal. Manual smoke via the visual companion + the deployed page is the visual verification.

### 8.3 useParcelMapView

- Returns `"2d"` when localStorage is empty.
- Returns the stored value when localStorage has `"2d"` or `"3d"`.
- Ignores junk values (e.g. `localStorage.getItem(...) === "asdf"` -> falls back to default `"2d"` without throwing).
- `setView` writes to localStorage.
- Does NOT touch localStorage during SSR (component renders correctly with the default during the server pass, then reads localStorage in `useEffect`).

### 8.4 ParcelInfoPanel

Update the existing "mounts ParcelMap with the auction's publicId" test to target `ParcelMapTabs` instead. No other panel-level changes.

### 8.5 Manual

- Drag-orbit feel.
- Scroll-zoom clamp.
- WebGL-disabled browser (or `chrome://flags` toggle) shows the fallback message + auto-switches to 2D.
- Reduced-motion media query disables damping.

## 9. Out of scope (filed as Issue #414)

- **Water plane** at SL sea level.
- **Snapshot-as-ground-texture** using the existing `parcel.snapshotUrl`.
- **Parcel walls** — extruding the perimeter wireframe downward to the ground.

Also explicitly not in this spec:

- Saving camera orientation per-auction or per-user. Camera resets on every mount.
- 3D per-cell click/hover with tooltip. The per-cell elevation readout lives in the 2D view; the 3D view is for "what does this look like."
- Real-time shadows from the directional light.
- Mobile-specific touch gestures beyond what OrbitControls ships (one-finger orbit, two-finger pinch-zoom; both come free from drei).

## 10. Decision log (2026-05-24)

- **Engine: three.js + @react-three/fiber + @react-three/drei.** Picked over manual canvas pseudo-3D (option B in the brainstorm) because the user wanted real rotation + zoom, and over raw WebGL (which would have been more plumbing for no benefit at this scale).
- **Placement: tab toggle.** Picked over stacked or side-by-side because mobile real estate is precious and only one view in memory at a time keeps the page light.
- **Persistence: localStorage, key `slpa:parcel-map:view`.** Tab choice carries across auctions; camera does not (camera resets per mount).
- **Bundle: lazy via `dynamic({ ssr: false })`.** Three.js ships only when the 3D tab is first opened. Default 2D visitor pays zero.
- **Parcel boundary: white `LineSegments`, not the 2D dim-outside.** Per the user's explicit note that dim/light reads poorly on the existing pseudo-3D mock.
- **No dim-outside in 3D.** The white wireframe is the parcel-vs-non-parcel signal.
- **Geometry: per-cell tops + south/east walls only.** Halves wall polys without visible degradation at typical orbit angles.
- **Lighting: one directional + ambient fill, no shadows.** Enough to differentiate top vs side; shadows add complexity for negligible gain at this scale.
- **WebGL-absent fallback: auto-switch to 2D, preserve the localStorage preference.** Lets the visitor's preference survive a device change.
- **Out of scope per issue #414:** water plane, snapshot texture, parcel walls. Deliberately deferred to keep this PR bounded.
