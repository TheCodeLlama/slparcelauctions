import { describe, expect, it } from "vitest";
import { rateToPercentDisplay, rateToPercentInput } from "./commission";

describe("rateToPercentInput", () => {
  it("strips IEEE-754 float artifacts (the 0.07 -> '7.000000000000001' bug)", () => {
    expect(rateToPercentInput(0.07)).toBe("7");
  });

  it("preserves a half-percent value", () => {
    expect(rateToPercentInput(0.075)).toBe("7.5");
  });

  it("renders a round zero correctly", () => {
    expect(rateToPercentInput(0)).toBe("0");
  });

  it("renders the DB column's max precision (DECIMAL(5,4))", () => {
    expect(rateToPercentInput(0.1234)).toBe("12.34");
  });

  it("renders the column's smallest non-zero step", () => {
    expect(rateToPercentInput(0.0001)).toBe("0.01");
  });

  it("renders 100% correctly", () => {
    expect(rateToPercentInput(1)).toBe("100");
  });

  it("returns empty string for null / undefined / non-finite inputs", () => {
    expect(rateToPercentInput(null)).toBe("");
    expect(rateToPercentInput(undefined)).toBe("");
    expect(rateToPercentInput(NaN)).toBe("");
    expect(rateToPercentInput(Infinity)).toBe("");
  });
});

describe("rateToPercentDisplay", () => {
  it("renders fixed two-decimal precision even for round values", () => {
    expect(rateToPercentDisplay(0.07)).toBe("7.00");
    expect(rateToPercentDisplay(0.1)).toBe("10.00");
  });

  it("strips the IEEE float artifact before formatting (same input that bit the form)", () => {
    // Without the integer-space rounding, `(0.07 * 100).toFixed(2)` would
    // still be "7.00" by accident, but `0.07 * 100` left raw can leak into
    // any downstream code that does its own arithmetic. The helper rounds
    // first so downstream computations stay clean.
    expect(rateToPercentDisplay(0.07)).toBe("7.00");
  });

  it("renders half-percent and partial values to two decimals", () => {
    expect(rateToPercentDisplay(0.075)).toBe("7.50");
    expect(rateToPercentDisplay(0.1234)).toBe("12.34");
  });

  it("returns empty string for null / undefined / non-finite inputs", () => {
    expect(rateToPercentDisplay(null)).toBe("");
    expect(rateToPercentDisplay(undefined)).toBe("");
    expect(rateToPercentDisplay(NaN)).toBe("");
  });
});
