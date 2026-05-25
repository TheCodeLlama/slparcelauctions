import { describe, it, expect } from "vitest";
import { gradientColor, slopeColor, dimOutside, MAP_COLORS } from "./colors";

describe("gradientColor (auto-scaled 2-stop green->red)", () => {
  it("returns solid green when delta <= 0 regardless of max", () => {
    expect(gradientColor(-2, 30)).toEqual(MAP_COLORS.green);
    expect(gradientColor(0, 30)).toEqual(MAP_COLORS.green);
  });

  it("returns solid red when delta >= max", () => {
    expect(gradientColor(30, 30)).toEqual(MAP_COLORS.red);
    expect(gradientColor(50, 30)).toEqual(MAP_COLORS.red);
  });

  it("returns solid green when max <= 0 (flat scene; no relief)", () => {
    expect(gradientColor(5, 0)).toEqual(MAP_COLORS.green);
    expect(gradientColor(5, -2)).toEqual(MAP_COLORS.green);
  });

  it("lerps green->red linearly at the midpoint (delta = max / 2)", () => {
    const g = MAP_COLORS.green;
    const r = MAP_COLORS.red;
    const mid = gradientColor(15, 30);
    expect(mid.r).toBeCloseTo((g.r + r.r) / 2, 0);
    expect(mid.g).toBeCloseTo((g.g + r.g) / 2, 0);
    expect(mid.b).toBeCloseTo((g.b + r.b) / 2, 0);
  });

  it("lerps green->red at 25% (delta = max / 4)", () => {
    const g = MAP_COLORS.green;
    const r = MAP_COLORS.red;
    const t = 0.25;
    const c = gradientColor(8, 32);
    expect(c.r).toBeCloseTo(g.r + (r.r - g.r) * t, 0);
    expect(c.g).toBeCloseTo(g.g + (r.g - g.g) * t, 0);
    expect(c.b).toBeCloseTo(g.b + (r.b - g.b) * t, 0);
  });

  it("lerps green->red at 75% (delta = 3 * max / 4)", () => {
    const g = MAP_COLORS.green;
    const r = MAP_COLORS.red;
    const t = 0.75;
    const c = gradientColor(24, 32);
    expect(c.r).toBeCloseTo(g.r + (r.r - g.r) * t, 0);
    expect(c.g).toBeCloseTo(g.g + (r.g - g.g) * t, 0);
    expect(c.b).toBeCloseTo(g.b + (r.b - g.b) * t, 0);
  });

  it("scales fully across a small max (region range = 4 m)", () => {
    // At max = 4m, delta=2m is the midpoint -- olive tones, not a sharp band.
    const g = MAP_COLORS.green;
    const r = MAP_COLORS.red;
    const c = gradientColor(2, 4);
    expect(c.r).toBeCloseTo((g.r + r.r) / 2, 0);
  });

  it("scales fully across a large max (region range = 60 m)", () => {
    // At max = 60m, delta=15m is 25% of the way -- still mostly green.
    const g = MAP_COLORS.green;
    const r = MAP_COLORS.red;
    const c = gradientColor(15, 60);
    const t = 0.25;
    expect(c.r).toBeCloseTo(g.r + (r.r - g.r) * t, 0);
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
