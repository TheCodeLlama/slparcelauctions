# Parcel Map: Land Use mode (+ 3D legend, toggle relocation)

**Date:** 2026-05-25
**Author:** brainstormed with @heath
**Status:** Draft

## Goal

Add a second 2D rendering mode to the auction-detail parcel map that paints every cell by its in-world land-use category (the listed parcel, abandoned Linden land, public for-sale parcels, protected Linden land, or uncategorized neighbor land). Users can toggle between Elevation and Land Use without leaving the 2D map. The same toggle-and-legend layout is applied to the existing 3D view, which also gains a gradient legend it lacks today.

## Motivation

The current parcel map answers "what does the terrain look like?" but says nothing about the surrounding land context. A bidder evaluating a parcel wants to know:

- **Is the listing surrounded by Linden buffer (roads, water, parks)?** Implies protected views and stable neighbors.
- **Is there abandoned land nearby?** Implies room to expand cheaply by claiming Linden-returned parcels.
- **Are competing parcels listed for sale in-world?** Implies alternatives the bidder could buy directly outside SLParcels.

These signals are visible in-world but invisible in our current view. A reference LSL scanner script the user wrote previously (provided inline in the brainstorming session, not committed) confirmed the categories are reliably detectable per-cell from `Parcel.Name` substring + `Parcel.Flags & ForSale`. The .NET bot already requests + caches all sim parcels via `RequestAllSimParcelsAsync`; this spec adds the per-cell classification + transport + render path.

## Categories

Per-cell, exactly five values. Encoded as a single byte each.

| Value | Name | Detection rule |
|-------|------|----------------|
| 0 | Other | Default. Cell belongs to a player-owned, not-for-sale parcel. Also covers missing-data cases (LocalID 0, parcel not in `sim.Parcels`, empty name). |
| 1 | Listed | Cell belongs to the listed parcel (matches the layout-bitmap cell). Wins over every other classification. |
| 2 | Abandoned | Parcel name contains `"Abandoned Land"` (Linden, claimable). |
| 3 | ForSale | `Parcel.Flags.HasFlag(ParcelFlags.ForSale)` is true and the parcel is not Linden-classified. `AuthBuyerID` is ignored: directed sales count, matching the LSL prior art and avoiding the implication that we know who the buyer is. |
| 4 | Protected | Parcel name contains `"Protected Land"` (Linden, off-limits). |

**Precedence when a cell could match more than one:** Listed > Protected > Abandoned > ForSale > Other. The listed parcel always wins (a seller could have the parcel still flagged for in-world sale; the auction context overrides). Linden categories (Protected, Abandoned) take precedence over ForSale because Linden parcels can technically carry the ForSale flag in odd states.

## Visual design

### Palette (locked with user)

| Category | Color |
|----------|-------|
| Listed | `rgb(0, 255, 0)` |
| Abandoned | `rgb(0, 0, 255)` |
| For Sale | `rgb(255, 255, 0)` |
| Protected | `rgb(255, 0, 0)` |
| Other / background | `rgb(255, 255, 255)` |

These are pure RGB primaries on pure white, deliberately. The Land Use view is a different visual register from the elevation view (which uses Tailwind 500-tone greens/reds with smooth gradients); the categorical mode reads as a flat data overlay.

### Layout (applied to all four mode views)

For both 2D maps and the 3D canvas, the chrome below the canvas is two rows:

1. **Row 1: legend** — content depends on the active mode.
   - 2D Elevation: existing gradient bar with `0 m` / `+N m` labels.
   - 2D Land Use: four swatches (Listed, Abandoned, For Sale, Protected) inline, no gradient.
   - 3D Elevation: gradient bar with `0 m` / `+N m` labels (new — does not exist today).
   - 3D Slope: gradient bar with `0°` / `45°` labels (new).
2. **Row 2: mode toggle** — single segmented chip, ARIA radio-group pattern, options vary per view:
   - 2D toggle: `Elevation` / `Land Use`.
   - 3D toggle: `Elevation` / `Slope`.

The existing 3D color-mode toggle that sits at `absolute top-2 right-2` over the canvas moves down to row 2; the 3D wrapper grows taller by the height of the two new rows (legend + toggle, roughly 28-32 px combined). The canvas itself keeps its `aspect-square w-full max-w-[320px]` constraint.

### Outline behaviour

- **Elevation modes (2D + 3D):** keep the existing cyan parcel outline. The outline is the only signal of where the listed parcel is.
- **Land Use mode (2D only):** drop the cyan outline. The Listed-green fill marks the parcel boundary unambiguously, and the outline would clash visually with the saturated category fills.

### Hover tooltip in Land Use mode

The 2D map already shows a tooltip on hover/keyboard focus with cell-position coords + elevation + in-parcel flag. In Land Use mode the tooltip shows the same coords but swaps elevation for the category name:

- `(28, 84): Listed parcel`
- `(120, 12): Protected (Linden)`
- `(232, 196): Abandoned (claimable)`
- `(48, 160): For sale in-world`
- `(60, 60): Other (private)`

No per-parcel name or owner in the tooltip in this spec — that would require shipping a parcel-name table alongside the cell bytes. Out of scope; can be added later if users ask.

## Bot pipeline

`ScanParcelHandler` already calls `RequestAllSimParcelsAsync` and `GetRegionParcelLocalIds` to build the layout bitmap. We add a third pass over the same `sim.Parcels` dict to classify each cell.

### Algorithm

```text
listedLocalId = parcelMap[listedRow, listedCol]  // from existing layout pass

for row in 0..63:
  for col in 0..63:
    localId = parcelMap[row, col]
    if localId == 0:
      cells[row, col] = Other
      log warning "missing localId at (col, row)" with rate-limiting
      continue
    if localId == listedLocalId:
      cells[row, col] = Listed
      continue
    if !sim.Parcels.TryGetValue(localId, out parcel):
      cells[row, col] = Other
      log warning "localId in ParcelMap but not in sim.Parcels at (col, row)"
      continue
    name = parcel.Name ?? ""
    if name.Contains("Protected Land", OrdinalIgnoreCase):
      cells[row, col] = Protected
    else if name.Contains("Abandoned Land", OrdinalIgnoreCase):
      cells[row, col] = Abandoned
    else if parcel.Flags.HasFlag(ParcelFlags.ForSale):
      cells[row, col] = ForSale
    else:
      cells[row, col] = Other
```

Per-cell classification is O(4096) dict lookups against a dict of ~5-50 entries. No deduplication needed; the loop is fast.

### Audit-driven constraints

Per the audit of existing `LibreMetaverseBotSession`:

- **No new fail-fast.** `RequestAllSimParcels` can time out leaving `sim.Parcels` partially populated. The handler proceeds with whatever's cached (existing posture for the layout bitmap). Land Use classification mirrors this: missing data ⇒ `Other`, plus a structured warning log so we can monitor partial-data rates in CloudWatch.
- **No new bot capability needed.** All fields used (`Parcel.Name`, `Parcel.Flags`, `Parcel.OwnerID`) are already read by `ReadParcelAsync` (`LibreMetaverseBotSession.cs:374-483`). The land-use pass uses the same `sim.Parcels` dict the existing layout pass uses; no extra network round trips.
- **No new task type.** Land Use cells ride along with the existing `SCAN_PARCEL` task.

### Transport

`ScanResultRequest` (bot → backend) gains one field:

```csharp
record ScanResultRequest(
    int GridSize,
    int CellSizeMeters,
    string LayoutCellsBase64,
    float HeightBaseMeters,
    float HeightStepMeters,
    string HeightCellsBase64,
    string LandUseCellsBase64  // NEW: 4096 bytes base64-encoded, values 0..4
);
```

## Backend storage

New JPA entity, parallel to `AuctionParcelHeightMap` and `AuctionParcelLayout`:

```java
@Entity
@Table(name = "auction_parcel_land_use")
public class AuctionParcelLandUse extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false, unique = true)
    private Auction auction;

    @Column(nullable = false)
    private int gridSize;   // 64

    @Column(nullable = false)
    private int cellSizeMeters;  // 4

    @Column(nullable = false, length = 4096)
    private byte[] cells;  // values 0..4

    @Column(nullable = false)
    private Instant scannedAt;
}
```

Flyway migration: `V<next>__auction_parcel_land_use.sql`. Single new table, FK to auctions, unique on auction_id so we can `INSERT ... ON CONFLICT` for the idempotent re-scan path.

The existing scan-ingest endpoint (`POST /api/v1/bot/parcel-scan/{taskId}/result`) extends to also persist the new field. Same 409-idempotent posture as today.

## Endpoint

Extend `GET /api/v1/auctions/{publicId}/parcel-scan` response to include the land-use cells:

```ts
interface ParcelScanResponse {
  gridSize: number;
  cellSizeMeters: number;
  layoutCellsBase64: string;
  heightCellsBase64: string;
  baseMeters: number;
  stepMeters: number;
  scannedAt: string;
  landUseCellsBase64: string | null;  // NEW; null for pre-existing scans without it
}
```

`null` instead of an empty string so the frontend can render `"Land Use unavailable"` for historical scans that pre-date this feature. Frontend treats `null` by disabling the Land Use radio option (toggle still renders, the Land Use option is `aria-disabled`).

## Frontend rendering

### New files

- `frontend/src/hooks/useParcelMap2DColorMode.ts` — localStorage-backed hook, same two-phase mount pattern as `useParcelMap3DColorMode`. Key: `slpa:parcel-map:2d-color`. Default: `"elevation"`. Type: `"elevation" | "landuse"`.
- `frontend/src/hooks/useParcelMap2DColorMode.test.tsx`
- `frontend/src/components/auction/ParcelMap2DColorModeToggle.tsx` — pure presentational ARIA radio group, mirrors `ParcelMap3DColorModeToggle`. Props: `{ mode, onChange, landUseAvailable, className }`. When `landUseAvailable === false`, the Land Use option renders with `aria-disabled="true"` and click handler is suppressed.
- `frontend/src/components/auction/ParcelMap2DColorModeToggle.test.tsx`
- `frontend/src/lib/parcelMap/landUseColors.ts` — pure category-to-rgb map + a `landUseCellColor(value: number) => Rgb` lookup. Mirrors the structure of `colors.ts` but is a separate file because the palette and semantics are independent.
- `frontend/src/lib/parcelMap/landUseColors.test.ts`
- `frontend/src/components/auction/ParcelMapLandUseLegend.tsx` — pure presentational row of four labelled swatches. Static; no props for now (no per-region label customization).
- `frontend/src/components/auction/ParcelMap3DLegend.tsx` — pure presentational legend for the 3D view. Props: `{ mode: "elevation" | "slope", maxDelta: number }`. Renders the gradient bar + the appropriate end labels.

### Modified files

- `frontend/src/components/auction/ParcelMap.tsx`
  - Wire `useParcelMap2DColorMode` + render `ParcelMap2DColorModeToggle` in the new row-2 slot.
  - Paint loop branches on mode: elevation path unchanged; landuse path reads `landUseCellsBase64`, decodes to `Uint8Array`, paints each cell from `landUseCellColor`. Cells without land-use data (null `landUseCellsBase64`) keep elevation path or render as Other-white if Land Use mode was somehow selected.
  - Hover/focus tooltip switches to category-name format in Land Use mode.
  - Cyan parcel outline rendered only in Elevation mode.
  - Existing inline `ParcelMapLegend` function (the gradient + `0 m` / `+N m` row that lives inside `ParcelMap.tsx` today) is renamed to `ParcelMapElevationLegend` to make the split explicit. `ParcelMap` now picks between `ParcelMapElevationLegend` and the new `ParcelMapLandUseLegend` based on mode. Both can stay inline functions within `ParcelMap.tsx` or be promoted to sibling files; implementer's call. (The `ParcelMapLandUseLegend.tsx` file listed in **New files** is the promoted form; if the implementer keeps it inline instead, that file is unneeded.)

- `frontend/src/components/auction/ParcelMap3D.tsx`
  - Move `<ParcelMap3DColorModeToggle>` from `absolute top-2 right-2` to a new row-2 slot below the canvas.
  - Add new `ParcelMap3DLegend` component rendered in the new row-1 slot. Two sub-cases: elevation (gradient + `0 m` / `+N m`) and slope (gradient + `0°` / `45°`).

- `frontend/src/components/auction/ParcelMap.test.tsx` — add Land Use mode rendering + tooltip + outline-hidden cases.
- `frontend/src/components/auction/ParcelMap3D.test.tsx` — assert legend renders in both modes, assert toggle moved below the canvas.
- `frontend/src/types/auction.ts` — extend `ParcelScanResponse` with `landUseCellsBase64`.
- `frontend/src/lib/parcelMap/encoding.ts` — add `decodeLandUseCell(landUseCells, row, col): number` helper (trivial single-byte read).

### Cell-by-cell paint posture

The Land Use mode does not dim outside-parcel cells (no `dimOutside` call). The whole region is shown at full saturation because the whole region is the point — neighbor categories are the data. The listed parcel green fill substitutes for the outline-as-signal.

## 3D legend (incidental, but in scope)

The 3D view does not have a legend today. Adding one is necessary to match the new chrome layout and is small. Two modes, both continuous gradients:

- **Elevation:** auto-scaled like the 2D elevation legend. `+N m` label uses `bounds.rMax - stats.parcelMin` rounded to nearest meter, same value the auto-scale gradient uses.
- **Slope:** fixed `0°` / `45°` labels (the gradient is fixed-saturation at π/4 per the slope spec).

The 3D legend lives in the same wrapper component as the 3D canvas and toggle; no new top-level routing.

## Out of scope

Filed for follow-ups if usage warrants:

- **Per-parcel hover detail** — tooltip showing the parcel name, owner display name, sale price for For Sale cells. Requires shipping a parcel-table alongside the cell bytes. Decided in brainstorm: defer.
- **Click-through to in-world** — clicking a cell opens `secondlife://...` URL. Useful but not in this slice.
- **Scan refresh during active auction** — periodic re-scan to keep land-use fresh. One-shot at listing creation is the chosen first cut (matches existing scan posture). Surface the `scannedAt` timestamp on the legend so users see the data age.
- **Public vs. directed For Sale split** — early brainstorm considered five categories. Collapsed to four per user decision.
- **Group-owned distinction** — group-owned cells classify as Other (unless the parcel is for sale or Linden). Not surfaced separately.
- **Linden Lab owner-UUID match as a Protected/Abandoned signal** — substring match on `Parcel.Name` is the authoritative path. UUID match could be a future hardening if name-renames by players cause misclassifications in practice.

## Acceptance criteria

### Functional

- A new auction's `parcel-scan` response includes `landUseCellsBase64` of length 5462 (base64 of 4096 bytes).
- Toggling the 2D map between Elevation and Land Use re-renders the canvas without a network round trip; mode persists across page reloads via `slpa:parcel-map:2d-color`.
- Toggling the 3D view between Elevation and Slope re-renders the canvas; the new legend strip updates its labels and gradient between modes; mode persists via the existing `slpa:parcel-map:3d-color`.
- Hover/focus tooltip in Land Use mode shows the category name in plain text matching the format in the Visual design section.
- Cyan parcel outline is rendered in Elevation mode and not in Land Use mode (verified by component test).
- Pre-existing scans (created before this feature) return `landUseCellsBase64: null`; the toggle's Land Use option is `aria-disabled` and does not fire `onChange` when clicked.

### Bot pipeline

- The bot loops 4096 cells, classifies each by the algorithm in **Bot pipeline > Algorithm**, base64-encodes the resulting 4096-byte array, and posts it as `LandUseCellsBase64`.
- Cells where `sim.Parcels.TryGetValue` returns false produce category `Other` and a structured warning log including `(col, row, localId)`.
- Cells with `localId == 0` produce category `Other` and a rate-limited warning log.
- Cells with the listed parcel's LocalID produce category `Listed` regardless of `Name` or `Flags`.

### Testing

- Bot: new unit test suite covers each category branch (Listed, Abandoned, Protected, ForSale, Other), partial-download tolerance (`sim.Parcels` missing a LocalID), name-renames (case-insensitive substring), and listed-parcel-wins precedence.
- Backend: existing scan-ingest test extended to round-trip the new field; null-load path returns the new field as null.
- Frontend: existing 2D + 3D test suites extended to cover the new toggle layout, legend swap, tooltip swap, and outline-hidden behaviour. `useParcelMap2DColorMode` gets a full test suite mirroring `useParcelMap3DColorMode`.

### Post-ship verification (the "verify our functions work" half)

After merging to main + Amplify deploy, run a manual scan against a real mainland region known to contain at least one of each category (the user has a candidate region in mind). Visually compare the rendered Land Use map against the in-world view (right-click parcel ⇒ About Land). Confirm:

- The listed parcel renders all-green.
- Abandoned parcels render blue and match the in-world `Abandoned Land` parcels.
- Protected roads / waterways render red and match the in-world `Protected Land` parcels.
- Any parcel flagged for sale in-world renders yellow.
- Private neighbor parcels render white.

If any cells misclassify, file a follow-up issue against this spec with the region name + cell coords; do not amend this spec.

## Implementation order (input for plan-writing)

1. Bot: extend `ScanResultRequest` + classification logic + tests.
2. Backend: Flyway migration + entity + DTO + endpoint extension + tests.
3. Frontend types: extend `ParcelScanResponse`.
4. Frontend hook + toggle component (2D) + tests.
5. Frontend hook layer for 2D legend selection + `ParcelMapLandUseLegend` component + tests.
6. Frontend `ParcelMap.tsx` wire-up: mode-aware paint, tooltip, outline gating.
7. Frontend 3D legend extraction into a component + relocate toggle below canvas + tests.
8. Manual Postman smoke against dev backend; bot integration smoke via `dotnet test`.
9. PR feat → dev → user reviews dev → main.
10. Post-deploy verification per the criteria above; file any misclassification follow-ups.
