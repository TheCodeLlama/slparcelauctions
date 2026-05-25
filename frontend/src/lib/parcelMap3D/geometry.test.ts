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

  it("produces upward-facing vertex normals (terrain points +Y on average)", () => {
    // Use a varying (not flat) input so vertex normals are computed from
    // genuine face normals, not degenerate zero-area triangles at edges.
    const heights = new Uint8Array(4096);
    for (let i = 0; i < heights.length; i++) heights[i] = i % 50;
    const upsampled = bicubicUpsample(decodeElevationGrid(heights, 22, 0.5));
    const stats = computeParcelStats(
      layoutWith([[10, 10]]), decodeElevationGrid(heights, 22, 0.5),
    )!;
    const geom = buildHeightfieldGeometry(upsampled, stats.parcelMin);
    const normals = geom.getAttribute("normal");
    let sumY = 0;
    for (let i = 0; i < normals.count; i++) sumY += normals.getY(i);
    const avgY = sumY / normals.count;
    // Any positive average proves the winding is correct. Flat terrain
    // would give avgY = 1; rolling terrain will be lower but still well
    // above 0. With bad (reversed) winding this value would be negative.
    expect(avgY).toBeGreaterThan(0.3);
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
