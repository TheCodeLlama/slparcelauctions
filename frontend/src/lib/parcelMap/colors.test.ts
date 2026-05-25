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
