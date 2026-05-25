import { describe, it, expect } from "vitest";
import {
  LAND_USE_COLORS,
  LAND_USE_CATEGORY,
  landUseCellColor,
  landUseCategoryLabel,
} from "./landUseColors";

describe("LAND_USE_COLORS palette is pure RGB primaries", () => {
  it("listed = pure green", () => {
    expect(LAND_USE_COLORS.listed).toEqual({ r: 0, g: 255, b: 0 });
  });
  it("abandoned = pure blue", () => {
    expect(LAND_USE_COLORS.abandoned).toEqual({ r: 0, g: 0, b: 255 });
  });
  it("forSale = pure yellow", () => {
    expect(LAND_USE_COLORS.forSale).toEqual({ r: 255, g: 255, b: 0 });
  });
  it("protected = pure red", () => {
    expect(LAND_USE_COLORS.protected).toEqual({ r: 255, g: 0, b: 0 });
  });
  it("other = pure white", () => {
    expect(LAND_USE_COLORS.other).toEqual({ r: 255, g: 255, b: 255 });
  });
});

describe("LAND_USE_CATEGORY numeric values match the bot/backend contract", () => {
  it("Other = 0", () => expect(LAND_USE_CATEGORY.Other).toBe(0));
  it("Listed = 1", () => expect(LAND_USE_CATEGORY.Listed).toBe(1));
  it("Abandoned = 2", () => expect(LAND_USE_CATEGORY.Abandoned).toBe(2));
  it("ForSale = 3", () => expect(LAND_USE_CATEGORY.ForSale).toBe(3));
  it("Protected = 4", () => expect(LAND_USE_CATEGORY.Protected).toBe(4));
});

describe("landUseCellColor maps every value 0..4 to the right swatch", () => {
  it("0 -> other", () => expect(landUseCellColor(0)).toEqual(LAND_USE_COLORS.other));
  it("1 -> listed", () => expect(landUseCellColor(1)).toEqual(LAND_USE_COLORS.listed));
  it("2 -> abandoned", () => expect(landUseCellColor(2)).toEqual(LAND_USE_COLORS.abandoned));
  it("3 -> forSale", () => expect(landUseCellColor(3)).toEqual(LAND_USE_COLORS.forSale));
  it("4 -> protected", () => expect(landUseCellColor(4)).toEqual(LAND_USE_COLORS.protected));
  it("falls back to other for out-of-range values (defensive)", () => {
    expect(landUseCellColor(99)).toEqual(LAND_USE_COLORS.other);
    expect(landUseCellColor(-1)).toEqual(LAND_USE_COLORS.other);
  });
});

describe("landUseCategoryLabel returns user-facing names", () => {
  it("0 -> 'Other (private)'", () => expect(landUseCategoryLabel(0)).toBe("Other (private)"));
  it("1 -> 'Listed parcel'", () => expect(landUseCategoryLabel(1)).toBe("Listed parcel"));
  it("2 -> 'Abandoned (claimable)'", () =>
    expect(landUseCategoryLabel(2)).toBe("Abandoned (claimable)"));
  it("3 -> 'For sale in-world'", () => expect(landUseCategoryLabel(3)).toBe("For sale in-world"));
  it("4 -> 'Protected (Linden)'", () => expect(landUseCategoryLabel(4)).toBe("Protected (Linden)"));
  it("falls back for out-of-range", () =>
    expect(landUseCategoryLabel(99)).toBe("Other (private)"));
});
