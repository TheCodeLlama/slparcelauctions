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

const TOP_VERTS = UPSAMPLED_GRID * UPSAMPLED_GRID;
const TOP_INDICES = (UPSAMPLED_GRID - 1) * (UPSAMPLED_GRID - 1) * 6;
const WALL_VERTS = 4 * 2 * UPSAMPLED_GRID;
const WALL_INDICES = 4 * (UPSAMPLED_GRID - 1) * 6;
const FLOOR_VERTS = 4;
const FLOOR_INDICES = 6;
const TOTAL_VERTS = TOP_VERTS + WALL_VERTS + FLOOR_VERTS;
const TOTAL_INDICES = TOP_INDICES + WALL_INDICES + FLOOR_INDICES;

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
    expect(southAvg[2]).toBeLessThan(-0.5);
    expect(northAvg[2]).toBeGreaterThan(0.5);
    expect(westAvg[0]).toBeLessThan(-0.5);
    expect(eastAvg[0]).toBeGreaterThan(0.5);
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
    const topR = colors.getX(0);
    const topG = colors.getY(0);
    const topB = colors.getZ(0);
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
    const elevR = elevColor0.getX(0);
    const slopeR = slopeColor0.getX(0);
    expect(slopeR).not.toBeCloseTo(elevR, 2);
  });
});
