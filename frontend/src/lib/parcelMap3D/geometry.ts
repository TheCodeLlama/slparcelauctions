import {
  BufferAttribute,
  BufferGeometry,
  Float32BufferAttribute,
} from "three";

import { gradientColor, slopeColor } from "@/lib/parcelMap/colors";
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

/**
 * Wall-vertex color multiplier vs the top-surface color at the same X/Z.
 * 0.55 was chosen during brainstorming as the visual weight of a "darker
 * shaded side" -- clearly distinct from the top while still recognizable
 * as the same terrain material. Both top and bottom edges of every wall
 * vertex use this multiplier so the wall reads as a uniformly-dimmed strip.
 */
const WALL_DIM = 0.55;
/**
 * Bottom-plane face color. Fixed neutral earth tone, not derived from the
 * terrain. The floor is rarely visible (only on aggressive low-angle orbit
 * that gets the camera below the region), so this is defensive coverage to
 * avoid a transparent void rather than a primary visual element.
 */
const FLOOR_COLOR = { r: 60, g: 50, b: 40 };

/**
 * Vertex color for a top-surface vertex, dispatched by color mode. Returns
 * {@link gradientColor}'s elevation-delta hue in {@code "elevation"} mode
 * or {@link slopeColor}'s slope-angle hue in {@code "slope"} mode.
 */
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
 * and (col % UPSAMPLE_FACTOR === 0) -- Catmull-Rom passes through its control
 * points by construction.
 *
 * Cost: ~17k Catmull-Rom evals per axis pass, twice; trivial on the main
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
    const rawY = uRow / UPSAMPLE_FACTOR;
    const row1Raw = Math.floor(rawY);
    // Clamp to valid rowsTemp row range and recompute t so the last upsampled
    // row (uRow = 128, rawY = 64.0) lands exactly on the last source row (63)
    // with t = 0 instead of reading an out-of-bounds row index.
    const row1 = Math.min(GRID - 1, row1Raw);
    const t = row1Raw >= GRID ? 0 : rawY - row1Raw;
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
 * Includes the top surface (129x129 vertices, smooth heightfield), four
 * extruded perimeter walls reaching down to {@code floorY}, and a bottom
 * floor quad at {@code floorY}. Vertex colors come from one of two helpers
 * depending on {@code mode}: elevation (delta from parcelMin) or slope (rad).
 * Walls reuse the perimeter top color multiplied by {@link WALL_DIM}; both
 * top and bottom edges of each wall share the same color. Floor uses a
 * fixed neutral earth tone.
 *
 * Vertex layout (used by tests asserting positions/colors/normals by index):
 *   [0 .. TOP_VERTS)                          top mesh, row-major (uRow * UPSAMPLED_GRID + uCol)
 *   [TOP_VERTS .. +PER_WALL_VERTS)            south wall, pairs of (top-edge, bottom-edge) by uCol
 *   [+PER_WALL_VERTS .. +2*PER_WALL_VERTS)    north wall, same pair ordering by uCol
 *   [+2*PER_WALL_VERTS .. +3*PER_WALL_VERTS)  west wall, pairs by uRow
 *   [+3*PER_WALL_VERTS .. +4*PER_WALL_VERTS)  east wall, pairs by uRow
 *   [TOP_VERTS + WALL_VERTS ..)               floor (4 vertices, SW SE NW NE)
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

  // Emit one wall strip. Each call writes `perimeterCount` vertex-pairs
  // (top-edge + bottom-edge at each perimeter point) and `perimeterCount - 1`
  // quads. The `quadIndices` callback maps the four base vertex offsets for
  // each quad (top-edge-A, bottom-edge-A, top-edge-B, bottom-edge-B) to 6
  // CCW triangle indices; winding direction differs per wall so face normals
  // point OUTWARD from the region center (south=-Z, north=+Z, west=-X,
  // east=+X). Wall color = top-surface color at the same perimeter X/Z,
  // multiplied by WALL_DIM for both the top and bottom edge of the wall.
  function emitWall(opts: {
    perimeterIndex: (i: number) => number;
    perimeterX: (i: number) => number;
    perimeterZ: (i: number) => number;
    perimeterCount: number;
    quadIndices: (
      baseTopA: number, baseBottomA: number,
      baseTopB: number, baseBottomB: number,
    ) => number[];
  }): number {
    const wallVertStart = vCursor;
    for (let i = 0; i < opts.perimeterCount; i++) {
      const topIdx = opts.perimeterIndex(i);
      const x = opts.perimeterX(i);
      const z = opts.perimeterZ(i);
      const topY = upsampled[topIdx];

      positions[vCursor * 3 + 0] = x;
      positions[vCursor * 3 + 1] = topY;
      positions[vCursor * 3 + 2] = z;
      positions[(vCursor + 1) * 3 + 0] = x;
      positions[(vCursor + 1) * 3 + 1] = floorY;
      positions[(vCursor + 1) * 3 + 2] = z;

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
      const baseTopA = wallVertStart + i * 2;
      const baseBottomA = baseTopA + 1;
      const baseTopB = wallVertStart + (i + 1) * 2;
      const baseBottomB = baseTopB + 1;
      const tri = opts.quadIndices(baseTopA, baseBottomA, baseTopB, baseBottomB);
      for (let t = 0; t < tri.length; t++) indices[iIdx++] = tri[t];
    }
    return vCursor;
  }

  // South wall: uRow = 0, z = 0. CCW from outside (-Z side) -> normal = -Z.
  // Viewed from -Z: uCol increases left to right. For -Z normal, each quad's
  // CCW winding (from -Z perspective) = topA, topB, bottomB, topA, bottomB, bottomA.
  vCursor = emitWall({
    perimeterIndex: (uCol) => 0 * UPSAMPLED_GRID + uCol,
    perimeterX: (uCol) => uCol * UPSAMPLED_SPACING_M,
    perimeterZ: () => 0,
    perimeterCount: UPSAMPLED_GRID,
    quadIndices: (baseTopA, baseBottomA, baseTopB, baseBottomB) => [
      baseTopA, baseTopB, baseBottomB,
      baseTopA, baseBottomB, baseBottomA,
    ],
  });

  // North wall: uRow = UPSAMPLED_GRID-1, z = max. CCW from outside (+Z side) -> normal = +Z.
  vCursor = emitWall({
    perimeterIndex: (uCol) => (UPSAMPLED_GRID - 1) * UPSAMPLED_GRID + uCol,
    perimeterX: (uCol) => uCol * UPSAMPLED_SPACING_M,
    perimeterZ: () => (UPSAMPLED_GRID - 1) * UPSAMPLED_SPACING_M,
    perimeterCount: UPSAMPLED_GRID,
    quadIndices: (baseTopA, baseBottomA, baseTopB, baseBottomB) => [
      baseTopA, baseBottomA, baseBottomB,
      baseTopA, baseBottomB, baseTopB,
    ],
  });

  // West wall: uCol = 0, x = 0. CCW from outside (-X side) -> normal = -X.
  vCursor = emitWall({
    perimeterIndex: (uRow) => uRow * UPSAMPLED_GRID,
    perimeterX: () => 0,
    perimeterZ: (uRow) => uRow * UPSAMPLED_SPACING_M,
    perimeterCount: UPSAMPLED_GRID,
    quadIndices: (baseTopA, baseBottomA, baseTopB, baseBottomB) => [
      baseTopA, baseBottomA, baseBottomB,
      baseTopA, baseBottomB, baseTopB,
    ],
  });

  // East wall: uCol = UPSAMPLED_GRID-1, x = max. CCW from outside (+X side) -> normal = +X.
  vCursor = emitWall({
    perimeterIndex: (uRow) => uRow * UPSAMPLED_GRID + (UPSAMPLED_GRID - 1),
    perimeterX: () => (UPSAMPLED_GRID - 1) * UPSAMPLED_SPACING_M,
    perimeterZ: (uRow) => uRow * UPSAMPLED_SPACING_M,
    perimeterCount: UPSAMPLED_GRID,
    quadIndices: (baseTopA, baseBottomA, baseTopB, baseBottomB) => [
      baseTopA, baseTopB, baseBottomB,
      baseTopA, baseBottomB, baseBottomA,
    ],
  });

  // ---- 3. Floor ----
  const floorStart = TOP_VERTS + WALL_VERTS;
  const REGION_END = (UPSAMPLED_GRID - 1) * UPSAMPLED_SPACING_M; // = REGION_M (256 m)
  const floorCorners: Array<[number, number, number]> = [
    [0, floorY, 0],
    [REGION_END, floorY, 0],
    [0, floorY, REGION_END],
    [REGION_END, floorY, REGION_END],
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
  // SW=0, SE=1, NW=2, NE=3. Clockwise from above = CCW from below -> -Y normal.
  indices[iIdx++] = floorStart + 0; indices[iIdx++] = floorStart + 3; indices[iIdx++] = floorStart + 2;
  indices[iIdx++] = floorStart + 0; indices[iIdx++] = floorStart + 1; indices[iIdx++] = floorStart + 3;

  const geometry = new BufferGeometry();
  geometry.setAttribute("position", new Float32BufferAttribute(positions, 3));
  geometry.setAttribute("color", new Float32BufferAttribute(colors, 3));
  geometry.setIndex(new BufferAttribute(indices, 1));
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
 * Default camera position + target. Camera sits at 45 deg azimuth, 30 deg
 * elevation above the region center; distance fits the full 256m region in
 * the viewport with 20% breathing room at FOV 50 deg.
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
 * SSR-safe (returns true during the Node pass -- the actual check defers to
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
