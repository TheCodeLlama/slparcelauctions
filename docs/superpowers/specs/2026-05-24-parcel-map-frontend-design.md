# Parcel Map (Frontend)

**Date:** 2026-05-24
**Status:** Awaiting user review.
**Builds on:** Parcel Scanner spec `docs/superpowers/specs/2026-05-23-parcel-scanner-design.md`.

## 1. Goal

Render the auction's per-auction parcel rasters (layout bitmap + region heightmap) as a single combined map on the listing detail page. One glance shows the parcel's shape AND the regional elevation context; hovering a cell shows its elevation and whether it belongs to the listed parcel.

This spec closes the "Parcel scanner: frontend raster rendering" item from `docs/implementation/DEFERRED_WORK.md` (deferred from the scanner spec section 7).

## 2. Architecture

```
ParcelInfoPanel (server component, existing)
    └── ParcelMap (NEW, "use client")
         ├── useParcelScan(publicId) -- React Query hook (NEW)
         │      └── GET /api/v1/auctions/{publicId}/parcel-scan (NEW endpoint)
         │             └── ParcelScanReadService -> AuctionParcelLayoutRepository
         │                                       +  AuctionParcelHeightMapRepository
         └── <canvas width={256} height={256}> + tooltip + keyboard cursor
```

One new public-no-auth backend endpoint, one new React Query hook, one new client component, one modified existing panel. Data is immutable per auction; aggressive HTTP caching.

## 3. Backend

### 3.1 Endpoint

`GET /api/v1/auctions/{publicId}/parcel-scan` on `AuctionController`.

- **Auth:** `permitAll`. Auction details are public; the rasters are public-by-implication. Matches the precedent for `parcel.snapshotUrl` bytes (CLAUDE.md notes that browser `<img>` fetches and JSON fetches from a server component both run anonymously).
- **Response 200** (`ParcelScanResponse` record):
  ```json
  {
    "gridSize": 64,
    "cellSizeMeters": 4,
    "layoutCellsBase64": "...",
    "heightCellsBase64": "...",
    "baseMeters": 22.5,
    "stepMeters": 0.5,
    "scannedAt": "2026-05-24T04:57:31Z"
  }
  ```
- **Response 404** with a ProblemDetail body whenever either raster row is absent (no scan yet, scan failed, or `parcelScanIncluded=false`). The frontend treats 404 as "scan unavailable, hide section" and surfaces nothing visible.
- **Caching:** response headers `Cache-Control: public, max-age=31536000, immutable`. Rasters are immutable after the bot scan per the scanner spec ("the auction's permanent record"). Browsers and any CDN cache aggressively; the frontend's React Query layer also keeps the in-memory copy with `staleTime: Infinity` for the page's lifetime.

### 3.2 Service + DTO

New files in package `com.slparcelauctions.backend.auction.parcelscan`:

- `dto/ParcelScanResponse.java` -- a record matching the JSON above.
- `ParcelScanReadService.java` -- `Optional<ParcelScanResponse> findForAuction(UUID publicId)`. `@Transactional(readOnly = true)`. Looks up `Auction` by publicId via `AuctionRepository.findByPublicId`, then both raster repos. Returns `Optional.empty()` if the auction itself is missing OR either raster row is missing; the controller returns 404 on `empty`.
- `ParcelScanDtoMapper.java` (or inline helper inside the service if trivial) -- encodes both `byte[] cells` arrays via `Base64.getEncoder()`.

Controller method on the existing `AuctionController`:

```java
@GetMapping("/{publicId}/parcel-scan")
public ResponseEntity<ParcelScanResponse> parcelScan(@PathVariable UUID publicId) {
    return parcelScanReadService.findForAuction(publicId)
        .map(body -> ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .body(body))
        .orElseThrow(() -> new AuctionParcelScanNotFoundException(publicId));
}
```

`AuctionParcelScanNotFoundException` is a new exception extending whatever the auction package's not-found exceptions do today; `AuctionExceptionHandler` maps it to 404 with code `PARCEL_SCAN_NOT_FOUND`. (Or, if simpler, reuse an existing not-found exception with a different code -- decide during implementation by reading the existing pattern.)

### 3.3 Security wiring

In `SecurityConfig` (or wherever the auction-public matcher set lives), add `/api/v1/auctions/*/parcel-scan` to the permitAll matcher list alongside the existing public auction endpoints.

## 4. Frontend

### 4.1 New files

- `frontend/src/types/auction.ts` -- add the `ParcelScanResponse` interface.
- `frontend/src/hooks/useParcelScan.ts` + `useParcelScan.test.tsx`.
- `frontend/src/components/auction/ParcelMap.tsx` + `ParcelMap.test.tsx`.
- `frontend/src/lib/parcelMap/colors.ts` + `colors.test.ts` (gradient + dim-outside math).
- `frontend/src/lib/parcelMap/encoding.ts` + `encoding.test.ts` (base64 -> Uint8Array decode, MSB-first bit reader, elevation-cell decode).

### 4.2 Modified

- `frontend/src/components/auction/ParcelInfoPanel.tsx` -- add `<ParcelMap publicId={auction.publicId} />` as a new sub-section after the existing parcel-detail content. Unconditional (the component returns `null` on missing data; no wrapping conditional needed).

### 4.3 Type

```ts
export interface ParcelScanResponse {
  gridSize: number;
  cellSizeMeters: number;
  layoutCellsBase64: string;
  heightCellsBase64: string;
  baseMeters: number;
  stepMeters: number;
  scannedAt: string;
}
```

### 4.4 Hook

`useParcelScan(publicId: string)` -- thin React Query wrapper:

- `queryKey: ["parcel-scan", publicId]`.
- `queryFn` does `fetch(apiUrl(`/api/v1/auctions/${publicId}/parcel-scan`))`; on `r.ok` returns `r.json()`, on `r.status === 404` returns `null`, on anything else throws.
- `staleTime: Infinity`, `gcTime: Infinity` (immutable data; cache for the page lifetime).
- Returns the standard `{data, isPending, isError}` triple. `data: null` is the "no scan" signal the caller branches on; `isError: true` is reserved for genuine network/5xx failures.

### 4.5 Component

`ParcelMap` is `"use client"`. Render contract:

- **Pending:** 256x256 skeleton placeholder with the same `aspect-square w-full max-w-[320px]` sizing as the canvas, using the codebase's existing skeleton primitive (look for `<Skeleton>` or a `bg-bg-subtle animate-pulse` pattern in sibling components).
- **`data === null`** (404 case) or **`isError === true`**: return `null`. ParcelInfoPanel collapses gracefully; no placeholder copy.
- **Loaded:** render

```tsx
<figure className="flex flex-col gap-2">
  <canvas
    ref={canvasRef}
    width={256}
    height={256}
    tabIndex={0}
    role="application"
    aria-label="Region parcel and elevation map, 64 by 64 cells"
    className="aspect-square w-full max-w-[320px] [image-rendering:pixelated] border border-border-subtle rounded-md"
    onMouseMove={...}
    onMouseLeave={...}
    onKeyDown={...}
  />
  <figcaption className="text-xs text-fg-muted">
    Parcel covers {parcelCellCount} of 4096 cells. Elevation range
    {' '}{parcelMin.toFixed(1)} m to {parcelMax.toFixed(1)} m.
  </figcaption>
  {tooltip && <ParcelMapTooltip {...tooltip} />}
  <div role="status" aria-live="polite" className="sr-only">{liveAnnouncement}</div>
</figure>
```

The canvas is painted once per data load via `getImageData` + per-cell pixel writes + `putImageData`. The boundary outline is drawn after the cell-fill pass via `ctx.fillRect` 1-pixel edges (cheaper than `strokeRect` per cell because we only draw on edges that border a non-parcel neighbor).

### 4.6 Render rules

For each of the 4096 region cells `(row, col)`:

1. **Decode elevation:** `elevMeters = baseMeters + (heightCells[row*64 + col] & 0xFF) * stepMeters`.
2. **Compute parcel statistics once per data load (memoized via `useMemo`):**
   - `parcelMin = min(elevMeters)` over cells where `layoutBit[row*64 + col] === 1`.
   - `parcelMax = max(...)` over the same set.
   - `parcelCellCount = count(layoutBit === 1)`.
3. **Per-cell color:** `delta = elevMeters - parcelMin`. Map via the gradient:
   - `delta <= 0`: solid green `rgb(34, 197, 94)` (Tailwind green-500 reference).
   - `0 < delta <= 4`: linear lerp green -> yellow `rgb(234, 179, 8)` (Tailwind yellow-500). 4 m is SL's terraforming per-parcel raise/lower limit.
   - `4 < delta <= 8`: linear lerp yellow -> red `rgb(239, 68, 68)` (Tailwind red-500). 8 m is the un-flattenable spread per spec.
   - `delta > 8`: solid red.
4. **Dim outside parcel:** if `layoutBit === 0`, lerp the rgb toward neutral gray `rgb(120, 120, 120)` by factor 0.6 (60% toward gray, 40% retained color).
5. **Boundary outline (separate pass):** for each parcel cell, for each of its 4 neighbors, if the neighbor is OUTSIDE the parcel (or off the grid), paint a 1-pixel bright cyan line `rgb(34, 211, 238)` (Tailwind cyan-400 reference) on that edge.

**Color literals + the verify guard.** The frontend has a `no-hex-colors` guard in `npm run verify`. The colors above are all `rgb(...)` strings (not hex), so they may pass. If they trip the guard, lift them into CSS custom properties on `globals.css` and read via `getComputedStyle(document.documentElement).getPropertyValue(...)` in a one-time module init. Decide during implementation by running the guard early.

### 4.7 Interactivity

**Hover tooltip:**

- `onMouseMove(e)`: compute `(row, col)` from `e.nativeEvent.offsetX / offsetY` divided by the canvas's bounding-rect dimensions (NOT the canvas's `width`/`height` attributes -- CSS upscales, so we need the displayed size).
- State `{row, col, elevM, inParcel}`. Render `<ParcelMapTooltip>` absolutely positioned ~12 px below + 12 px right of cursor (the parent `<figure>` is `relative`).
- Tooltip content:
  - Line 1: `Cell (row, col)`.
  - Line 2: `Elevation X.X m`.
  - Line 3: `In parcel` or `Outside parcel`.
- `onMouseLeave`: clear tooltip state.

**Keyboard navigation:**

- `tabIndex={0}` makes the canvas focusable.
- `onKeyDown`: `ArrowUp/Down/Left/Right` move a focused-cell cursor `(focusRow, focusCol)`, clamped to `[0, 63]`. Initial position on first key press: `(32, 32)` (region center) if no prior cursor.
- The focused cell is drawn with a 1-px white inner outline (over the cell-fill, under the parcel boundary). Re-paint on cursor change.
- An `aria-live="polite"` `role="status"` `<div>` re-announces: `Cell (row, col). Elevation X.X m. In parcel.` on every cursor move and on every mouse hover. Same content as the tooltip; screen readers get it for free.

### 4.8 Encoding helpers (`lib/parcelMap/encoding.ts`)

Exports:

```ts
export function decodeBase64ToBytes(s: string): Uint8Array;
export function isCellInParcel(layoutCells: Uint8Array, row: number, col: number): boolean;
export function decodeElevationCell(
  heightCells: Uint8Array, row: number, col: number,
  baseMeters: number, stepMeters: number
): number;
```

`decodeBase64ToBytes` uses native `atob` + a `for`-loop into a `Uint8Array`. No external dependency.

`isCellInParcel` reads bit `7 - (col % 8)` of byte `row * 8 + (col / 8)` (MSB-first, matching the backend encoding).

`decodeElevationCell` returns `baseMeters + (heightCells[row * 64 + col] & 0xFF) * stepMeters`.

## 5. Fallback + accessibility

- **Missing scan (404 or null data):** `ParcelMap` returns `null`. `ParcelInfoPanel` shows nothing in that slot. No placeholder copy; the scan is non-essential, its absence shouldn't draw attention.
- **Pending:** Skeleton placeholder so the layout doesn't jump when data arrives.
- **Genuine network failure (5xx):** `isError === true` also returns `null`. We could surface an inline error toast in a future revision; this spec deliberately keeps the failure silent to match the "non-gating, non-essential" posture from the scanner spec.
- **Screen reader:** The always-present `<figcaption>` reports parcel cell count + elevation range. The `aria-live` region announces per-cell details on hover/keyboard. The `role="application"` + `aria-label` on the canvas frame the widget for assistive tech.
- **Color blindness:** The tooltip's elevation reading is the source-of-truth signal; the gradient is a supplement. The cyan boundary outline has high contrast against all gradient stops (green / yellow / red), verified during implementation; if cyan-on-yellow proves insufficient, fall back to a 2-px white inner + 1-px black outer outline.
- **Pixelated upscale:** CSS `image-rendering: pixelated` keeps the 256x256 canvas crisp when CSS scales it to fill its container. No blur, no antialiasing artifacts on the parcel-boundary edges.

## 6. Testing

### 6.1 Backend

`AuctionParcelScanReadControllerTest` -- `@SpringBootTest + @AutoConfigureMockMvc`. NEVER `@WebMvcTest` (project convention -- the slice loads too little of the security + JPA wiring this endpoint depends on):

- **Happy path:** seed an auction + both raster rows; assert 200, all 7 fields present, base64 strings decode to 512 / 4096 bytes, `scannedAt` non-null.
- **404 missing layout:** seed auction + heightmap only; assert 404 + ProblemDetail `code: "PARCEL_SCAN_NOT_FOUND"`.
- **404 missing heightmap:** mirror, the other way (defensive; the bot writes both atomically, but the join may surface a partial state).
- **404 unknown auction:** request a UUID with no auction row.
- **Public access:** the happy-path request WITHOUT an `Authorization` header still returns 200.
- **Cache headers:** assert the 200 response carries `Cache-Control: public, max-age=31536000, immutable`.

### 6.2 Frontend

- `useParcelScan.test.tsx`: MSW handler returning the 200 body -> hook resolves with the data; handler returning 404 -> hook resolves with `data: null`; handler returning 500 -> hook resolves with `isError: true`.
- `lib/parcelMap/colors.test.ts`: assert the four anchor deltas (-2, 0, 4, 8, 12 m relative to parcel-min) produce the expected RGB; assert the dim-outside lerp at 60% returns the expected fade for a known input color.
- `lib/parcelMap/encoding.test.ts`: `decodeBase64ToBytes` of a 4-byte known-base64 string round-trips; `isCellInParcel` returns true for byte 0 bit 7 (cell `(0, 0)`) when the byte is `0x80`; `decodeElevationCell` decodes a known cell to a known elevation.
- `ParcelMap.test.tsx` (RTL; canvas mocked via the existing pattern -- check if the codebase already has a `vitest-canvas-mock` or similar setup; if not, stub `HTMLCanvasElement.prototype.getContext` in the test setup):
  - returns `null` when hook's `data === null`.
  - renders the canvas + figcaption summary on loaded data; figcaption text contains the parcel cell count and elevation range.
  - `onMouseMove` at a known canvas pixel updates the tooltip with the right cell coords, elevation, and in/out status.
  - `onKeyDown` `ArrowRight` from initial position `(32, 32)` moves the focused-cell cursor to `(32, 33)` and the `aria-live` region's text updates accordingly.
- `ParcelInfoPanel.test.tsx` (existing): add one case asserting `<ParcelMap>` is rendered with the auction's publicId prop.

### 6.3 Postman

Add to the SLPA collection's auction folder:
- `GET /api/v1/auctions/{{auctionId}}/parcel-scan` -- happy-path, `pm.test` asserting 200 + the 7 fields.
- A sibling against a known-no-scan auction asserting 404 + the problem-detail shape.

## 7. Out of scope

- Server-rendered PNG fallback (decided 2026-05-24: client canvas).
- Side-by-side or toggle layouts (decided 2026-05-24: one combined map).
- Click-to-copy SL world coordinates (decided 2026-05-24: hover + keyboard only, no click action).
- "Scan unavailable" placeholder copy (decided 2026-05-24: hide section entirely). Paid-upgrade work may revisit this for the seller view.
- Periodic rescan / refresh on stale data (rasters are immutable per the scanner spec; aggressive caching is correct).
- Admin GET endpoint exposing raw raster bytes for debugging (separate DEFERRED_WORK item; the public endpoint built here is what visitors see, not a debugging surface).
- Mobile-specific touch interactions (long-press to "hover", swipe, pinch-zoom). The component is responsive (`w-full max-w-[320px]`) but the tooltip is mouse-only in this slice.

## 8. Decision log (2026-05-24)

- **Layout:** one combined map (heightmap base + parcel-cell highlight) over side-by-side or toggle.
- **Render path:** client canvas with a new GET endpoint over server-rendered PNG or DTO-embedded bytes.
- **Interactivity:** hover tooltip with elevation + in/out + keyboard equivalent over static-only or click-to-copy.
- **Missing-scan fallback:** hide section entirely over "scan unavailable" placeholder.
- **Parcel highlight:** dim non-parcel cells (~60% toward gray) + 1-px cyan boundary outline over outline-only or solid-color overlay.
- **Gradient anchors:** delta-vs-parcel-min, green at 0, yellow at 4 m (terraforming limit), red at 8 m (un-flattenable spread). Linear lerp between anchors.
- **Caching:** `Cache-Control: public, max-age=31536000, immutable` -- rasters are per-auction-permanent per the scanner spec.
