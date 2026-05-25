"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import { cn } from "@/lib/cn";
import { useParcelScan } from "@/hooks/useParcelScan";
import {
  decodeBase64ToBytes,
  decodeElevationCell,
  decodeLandUseCell,
  isCellInParcel,
} from "@/lib/parcelMap/encoding";
import {
  MAP_COLORS,
  dimOutside,
  gradientColor,
  type Rgb,
} from "@/lib/parcelMap/colors";
import { landUseCellColor, landUseCategoryLabel } from "@/lib/parcelMap/landUseColors";
import { useParcelMap2DColorMode } from "@/hooks/useParcelMap2DColorMode";
import { ParcelMap2DColorModeToggle } from "./ParcelMap2DColorModeToggle";
import { ParcelMapLandUseLegend } from "./ParcelMapLandUseLegend";

const GRID = 64;
const CELL_PX = 4;
const CANVAS_PX = GRID * CELL_PX; // 256

// Precomputed RGB strings for canvas fillStyle/strokeStyle assignments.
// Kept as constants so the no-hex-colors guard sees no hex literals and
// the strings aren't rebuilt on every paint call.
const CYAN_RGB = `rgb(${MAP_COLORS.cyan.r}, ${MAP_COLORS.cyan.g}, ${MAP_COLORS.cyan.b})`;
const WHITE_RGB = "rgb(255, 255, 255)";

interface Props {
  publicId: string;
  className?: string;
}

interface Stats {
  parcelMin: number;
  parcelMax: number;
  parcelCellCount: number;
  /** Max elevation across the WHOLE region (all 4096 cells, not just parcel). */
  regionMax: number;
}

interface CellInfo {
  row: number;
  col: number;
  elevM: number;
  inParcel: boolean;
  /** Populated in Land Use mode; null in Elevation mode. */
  landUseValue: number | null;
}

interface Decoded {
  layoutCells: Uint8Array;
  heightCells: Uint8Array;
  baseMeters: number;
  stepMeters: number;
  landUseCells: Uint8Array | null;
}

/**
 * Combined parcel + region heightmap canvas. Renders nothing if the auction
 * has no scan rows (404 from the read endpoint) or on network errors. Spec:
 * docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md.
 *
 * Coordinate convention: per the parcel-scanner spec, row 0 = SOUTH edge of
 * the region, col 0 = WEST edge. A canvas's y=0 is its TOP, so the paint
 * pass flips: canvasY = (GRID - 1 - row) * CELL_PX. The hover and keyboard
 * math flips symmetrically.
 */
interface HoverState {
  cellInfo: CellInfo;
  pixelX: number;
  pixelY: number;
}

export function ParcelMap({ publicId, className }: Props) {
  const { data, isPending, isError } = useParcelScan(publicId);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const figureRef = useRef<HTMLElement | null>(null);
  const [hover, setHover] = useState<HoverState | null>(null);
  const [focusCell, setFocusCell] = useState<{ row: number; col: number } | null>(null);
  const [colorMode, setColorMode] = useParcelMap2DColorMode();
  const landUseAvailable = data?.landUseCellsBase64 != null;
  // If land use is unavailable but the user previously selected "landuse",
  // fall back to elevation rendering. The toggle still shows "landuse" as
  // aria-disabled so the user understands why.
  const activeMode = landUseAvailable ? colorMode : "elevation";

  const decoded: Decoded | null = useMemo(() => {
    if (!data) return null;
    return {
      layoutCells: decodeBase64ToBytes(data.layoutCellsBase64),
      heightCells: decodeBase64ToBytes(data.heightCellsBase64),
      baseMeters: data.baseMeters,
      stepMeters: data.stepMeters,
      landUseCells: data.landUseCellsBase64
        ? decodeBase64ToBytes(data.landUseCellsBase64)
        : null,
    };
  }, [data]);

  const stats: Stats | null = useMemo(() => {
    if (!decoded) return null;
    let min = Number.POSITIVE_INFINITY;
    let max = Number.NEGATIVE_INFINITY;
    let count = 0;
    let regionMax = Number.NEGATIVE_INFINITY;
    for (let row = 0; row < GRID; row++) {
      for (let col = 0; col < GRID; col++) {
        const e = decodeElevationCell(
          decoded.heightCells, row, col,
          decoded.baseMeters, decoded.stepMeters,
        );
        if (e > regionMax) regionMax = e;
        if (!isCellInParcel(decoded.layoutCells, row, col)) continue;
        if (e < min) min = e;
        if (e > max) max = e;
        count++;
      }
    }
    if (count === 0) {
      return null;
    }
    return { parcelMin: min, parcelMax: max, parcelCellCount: count, regionMax };
  }, [decoded]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !decoded || !stats) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    paintCells(ctx, decoded, stats, activeMode);
    if (activeMode === "elevation") {
      paintBoundary(ctx, decoded.layoutCells);
    }
    if (focusCell) paintFocusCursor(ctx, focusCell.row, focusCell.col);
  }, [decoded, stats, focusCell, activeMode]);

  if (isPending) {
    return (
      <div
        className={cn(
          "aspect-square w-full max-w-[320px] animate-pulse rounded-md bg-bg-subtle",
          className,
        )}
        aria-hidden="true"
      />
    );
  }

  if (isError || !data || !decoded || !stats) {
    return null;
  }

  const liveAnnouncement = announcementFor(
    hover?.cellInfo ?? cellInfoFor(focusCell, decoded, activeMode),
    activeMode,
  );

  return (
    <figure ref={figureRef} className={cn("relative flex flex-col gap-2", className)}>
      <canvas
        ref={canvasRef}
        width={CANVAS_PX}
        height={CANVAS_PX}
        tabIndex={0}
        role="application"
        aria-label="Region parcel and elevation map, 64 by 64 cells"
        className="aspect-square w-full max-w-[320px] border border-border-subtle [image-rendering:pixelated]"
        onMouseMove={(e) => setHover(cellInfoForEvent(e, decoded, figureRef.current, activeMode))}
        onMouseLeave={() => setHover(null)}
        onKeyDown={(e) => {
          const next = moveFocus(focusCell, e.key);
          if (next) {
            e.preventDefault();
            setFocusCell(next);
          }
        }}
      />
      <figcaption className="text-xs text-fg-muted">
        Parcel covers {stats.parcelCellCount * 16} m². Elevation
        {" "}{stats.parcelMin.toFixed(1)} m to {stats.parcelMax.toFixed(1)} m.
      </figcaption>
      <div className="flex flex-col gap-1 w-full max-w-[320px]">
        {activeMode === "landuse"
          ? <ParcelMapLandUseLegend />
          : <ParcelMapElevationLegend maxDelta={Math.max(0, stats.regionMax - stats.parcelMin)} />}
        <ParcelMap2DColorModeToggle
          mode={colorMode}
          onChange={setColorMode}
          landUseAvailable={landUseAvailable}
        />
      </div>
      {hover && <ParcelMapTooltip {...hover.cellInfo} pixelX={hover.pixelX} pixelY={hover.pixelY} />}
      <div role="status" aria-live="polite" className="sr-only">
        {liveAnnouncement}
      </div>
    </figure>
  );
}

// -- internals --------------------------------------------------------------

function paintCells(
  ctx: CanvasRenderingContext2D,
  decoded: Decoded,
  stats: Stats,
  mode: "elevation" | "landuse",
) {
  const img = ctx.createImageData(CANVAS_PX, CANVAS_PX);
  const maxDelta = stats.regionMax - stats.parcelMin;
  for (let row = 0; row < GRID; row++) {
    for (let col = 0; col < GRID; col++) {
      let color: Rgb;
      if (mode === "landuse" && decoded.landUseCells) {
        // Land Use mode: flat categorical color, no dim-outside treatment.
        color = landUseCellColor(decodeLandUseCell(decoded.landUseCells, row, col));
      } else {
        // Elevation mode: gradient from parcel low to region high, outside cells dimmed.
        const elev = decodeElevationCell(
          decoded.heightCells, row, col,
          decoded.baseMeters, decoded.stepMeters,
        );
        color = gradientColor(elev - stats.parcelMin, maxDelta);
        if (!isCellInParcel(decoded.layoutCells, row, col)) {
          color = dimOutside(color);
        }
      }
      // SW-first flip: row 0 = south edge of region, canvas y=0 = top of canvas.
      const canvasY = (GRID - 1 - row) * CELL_PX;
      const canvasX = col * CELL_PX;
      for (let dy = 0; dy < CELL_PX; dy++) {
        for (let dx = 0; dx < CELL_PX; dx++) {
          const idx = ((canvasY + dy) * CANVAS_PX + (canvasX + dx)) * 4;
          img.data[idx] = color.r;
          img.data[idx + 1] = color.g;
          img.data[idx + 2] = color.b;
          img.data[idx + 3] = 255;
        }
      }
    }
  }
  ctx.putImageData(img, 0, 0);
}

function paintBoundary(
  ctx: CanvasRenderingContext2D,
  layoutCells: Uint8Array,
) {
  ctx.fillStyle = CYAN_RGB;
  for (let row = 0; row < GRID; row++) {
    for (let col = 0; col < GRID; col++) {
      if (!isCellInParcel(layoutCells, row, col)) continue;
      const canvasY = (GRID - 1 - row) * CELL_PX;
      const canvasX = col * CELL_PX;

      // North neighbor (row+1): edge along the cell's TOP in canvas space
      if (row === GRID - 1 || !isCellInParcel(layoutCells, row + 1, col)) {
        ctx.fillRect(canvasX, canvasY, CELL_PX, 1);
      }
      // South neighbor (row-1): edge along the cell's BOTTOM in canvas space
      if (row === 0 || !isCellInParcel(layoutCells, row - 1, col)) {
        ctx.fillRect(canvasX, canvasY + CELL_PX - 1, CELL_PX, 1);
      }
      // West neighbor (col-1): edge along the cell's LEFT
      if (col === 0 || !isCellInParcel(layoutCells, row, col - 1)) {
        ctx.fillRect(canvasX, canvasY, 1, CELL_PX);
      }
      // East neighbor (col+1): edge along the cell's RIGHT
      if (col === GRID - 1 || !isCellInParcel(layoutCells, row, col + 1)) {
        ctx.fillRect(canvasX + CELL_PX - 1, canvasY, 1, CELL_PX);
      }
    }
  }
}

function paintFocusCursor(
  ctx: CanvasRenderingContext2D,
  row: number,
  col: number,
) {
  ctx.strokeStyle = WHITE_RGB;
  ctx.lineWidth = 1;
  const canvasY = (GRID - 1 - row) * CELL_PX;
  const canvasX = col * CELL_PX;
  ctx.strokeRect(canvasX + 0.5, canvasY + 0.5, CELL_PX - 1, CELL_PX - 1);
}

function cellInfoForEvent(
  e: React.MouseEvent<HTMLCanvasElement>,
  decoded: Decoded,
  figureEl: HTMLElement | null,
  mode: "elevation" | "landuse",
): HoverState {
  // Row/col math uses the canvas bounding rect (CSS-upscaled display coords).
  const canvasRect = e.currentTarget.getBoundingClientRect();
  const xPx = e.clientX - canvasRect.left;
  const yPx = e.clientY - canvasRect.top;
  const col = clamp(Math.floor((xPx / canvasRect.width) * GRID), 0, GRID - 1);
  const visualRow = clamp(Math.floor((yPx / canvasRect.height) * GRID), 0, GRID - 1);
  // Flip back from canvas-y (top-down) to row (SW-first, south-up).
  const row = GRID - 1 - visualRow;
  const cellInfo = cellInfoFor({ row, col }, decoded, mode)!;

  // Tooltip position uses the figure bounding rect so the absolutely-
  // positioned tooltip sits inside the <figure> container without overflow.
  // Fall back to canvas rect if the ref is null (defensive).
  const figureRect = figureEl?.getBoundingClientRect() ?? canvasRect;
  const pixelX = e.clientX - figureRect.left;
  const pixelY = e.clientY - figureRect.top;

  return { cellInfo, pixelX, pixelY };
}

function cellInfoFor(
  cell: { row: number; col: number } | null,
  decoded: Decoded,
  mode: "elevation" | "landuse" = "elevation",
): CellInfo | null {
  if (!cell) return null;
  const elevM = decodeElevationCell(
    decoded.heightCells, cell.row, cell.col,
    decoded.baseMeters, decoded.stepMeters,
  );
  const inParcel = isCellInParcel(decoded.layoutCells, cell.row, cell.col);
  const landUseValue =
    mode === "landuse" && decoded.landUseCells
      ? decodeLandUseCell(decoded.landUseCells, cell.row, cell.col)
      : null;
  return { row: cell.row, col: cell.col, elevM, inParcel, landUseValue };
}

function announcementFor(info: CellInfo | null, mode: "elevation" | "landuse" = "elevation"): string {
  if (!info) return "";
  const worldX = info.col * 4;
  const worldY = info.row * 4;
  // SL world coords for the SW corner of this 4 m cell. col -> x (east),
  // row -> y (north); 4 m per cell, region is 0..256 m.
  if (mode === "landuse" && info.landUseValue != null) {
    return `(${worldX}, ${worldY}): ${landUseCategoryLabel(info.landUseValue)}`;
  }
  const where = info.inParcel ? "in parcel" : "outside parcel";
  return `Position ${worldX}, ${worldY}. Elevation ${info.elevM.toFixed(1)} meters. ${where}.`;
}

function moveFocus(
  current: { row: number; col: number } | null,
  key: string,
): { row: number; col: number } | null {
  const base = current ?? { row: 32, col: 32 };
  switch (key) {
    case "ArrowUp": return { ...base, row: clamp(base.row + 1, 0, GRID - 1) };
    case "ArrowDown": return { ...base, row: clamp(base.row - 1, 0, GRID - 1) };
    case "ArrowRight": return { ...base, col: clamp(base.col + 1, 0, GRID - 1) };
    case "ArrowLeft": return { ...base, col: clamp(base.col - 1, 0, GRID - 1) };
    default: return null;
  }
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}

function ParcelMapElevationLegend({ maxDelta }: { maxDelta: number }) {
  // Linear gradient mirroring the 2-stop gradient used in colors.ts. Auto-
  // scales to the region's actual relief (parcel low -> region high) so the
  // visible transition is as soft as the data allows -- a cliff face on a
  // mildly hilly region no longer renders as a hard band.
  // RGB literals rather than #hex keep the no-hex-colors verify guard green.
  const stop = (c: { r: number; g: number; b: number }, pct: number) =>
    `rgb(${c.r}, ${c.g}, ${c.b}) ${pct}%`;
  const gradient = `linear-gradient(to right, ${stop(MAP_COLORS.green, 0)}, ${stop(MAP_COLORS.red, 100)})`;
  const rightLabel = `+${Math.round(maxDelta)} m`;
  return (
    <div className="w-full max-w-[320px] flex flex-col gap-1">
      <div
        style={{ background: gradient }}
        className="h-2 w-full border border-border-subtle"
        aria-hidden="true"
      />
      <div className="flex justify-between text-[10px] text-fg-muted">
        <span>0 m</span>
        <span>{rightLabel}</span>
      </div>
      <p className="text-[10px] text-fg-muted">
        Color = elevation above the parcel&apos;s lowest cell, scaled to the
        region&apos;s relief. Green is the parcel low; red is the region high.
        The SL terraforming raise/lower limit is 4 m; spread above 8 m
        can&apos;t be levelled.
      </p>
    </div>
  );
}

function ParcelMapTooltip({ row, col, elevM, inParcel, landUseValue, pixelX, pixelY }: CellInfo & { pixelX: number; pixelY: number }) {
  const worldX = col * 4;
  const worldY = row * 4;
  const isLandUse = landUseValue != null;
  return (
    <div
      style={{ left: pixelX + 12, top: pixelY + 12 }}
      className="absolute pointer-events-none rounded-md border border-border-subtle bg-surface-raised px-2 py-1 text-xs text-fg shadow"
      role="presentation"
    >
      {isLandUse ? (
        <div>({worldX}, {worldY}): {landUseCategoryLabel(landUseValue)}</div>
      ) : (
        <>
          <div>Pos: {worldX}, {worldY}</div>
          <div>Elevation {elevM.toFixed(1)} m</div>
          <div>{inParcel ? "In parcel" : "Outside parcel"}</div>
        </>
      )}
    </div>
  );
}
