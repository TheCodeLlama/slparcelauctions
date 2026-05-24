import { describe, it, expect } from "vitest";
import { gradientColor, dimOutside, MAP_COLORS } from "./colors";

describe("gradientColor", () => {
  it("returns solid green when delta <= 0", () => {
    expect(gradientColor(-2)).toEqual(MAP_COLORS.green);
    expect(gradientColor(0)).toEqual(MAP_COLORS.green);
  });

  it("returns yellow at delta = 4 (terraforming limit)", () => {
    expect(gradientColor(4)).toEqual(MAP_COLORS.yellow);
  });

  it("returns red at delta = 8 (un-flattenable spread)", () => {
    expect(gradientColor(8)).toEqual(MAP_COLORS.red);
  });

  it("returns solid red when delta > 8", () => {
    expect(gradientColor(12)).toEqual(MAP_COLORS.red);
  });

  it("lerps green->yellow at delta = 2 (midpoint of first segment)", () => {
    const g = MAP_COLORS.green;
    const y = MAP_COLORS.yellow;
    const mid = gradientColor(2);
    expect(mid.r).toBeCloseTo((g.r + y.r) / 2, 0);
    expect(mid.g).toBeCloseTo((g.g + y.g) / 2, 0);
    expect(mid.b).toBeCloseTo((g.b + y.b) / 2, 0);
  });

  it("lerps yellow->red at delta = 6 (midpoint of second segment)", () => {
    const y = MAP_COLORS.yellow;
    const r = MAP_COLORS.red;
    const mid = gradientColor(6);
    expect(mid.r).toBeCloseTo((y.r + r.r) / 2, 0);
    expect(mid.g).toBeCloseTo((y.g + r.g) / 2, 0);
    expect(mid.b).toBeCloseTo((y.b + r.b) / 2, 0);
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
