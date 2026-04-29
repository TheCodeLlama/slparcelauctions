import { describe, it, expect } from "vitest";
import { resolveListingHeadline } from "./resolveListingHeadline";

describe("resolveListingHeadline", () => {
  it("prefers title when non-blank", () => {
    expect(
      resolveListingHeadline({
        title: "Premium Waterfront",
        parcelDescription: "Lot",
        regionName: "Tula",
      }),
    ).toBe("Premium Waterfront");
  });

  it("trims title whitespace before checking blankness", () => {
    expect(
      resolveListingHeadline({
        title: "   ",
        parcelDescription: "Lot",
        regionName: "Tula",
      }),
    ).toBe("Lot");
    expect(
      resolveListingHeadline({
        title: "  Premium  ",
        parcelDescription: "Lot",
        regionName: "Tula",
      }),
    ).toBe("Premium");
  });

  it("falls back to parcelDescription when title is null or blank", () => {
    expect(
      resolveListingHeadline({
        title: null,
        parcelDescription: "Lot",
        regionName: "Tula",
      }),
    ).toBe("Lot");
    expect(
      resolveListingHeadline({
        title: undefined,
        parcelDescription: "Lot",
        regionName: "Tula",
      }),
    ).toBe("Lot");
    expect(
      resolveListingHeadline({
        title: "",
        parcelDescription: "Lot",
        regionName: "Tula",
      }),
    ).toBe("Lot");
  });

  it("falls back to regionName when title and description are both blank", () => {
    expect(
      resolveListingHeadline({
        title: null,
        parcelDescription: null,
        regionName: "Tula",
      }),
    ).toBe("Tula");
    expect(
      resolveListingHeadline({
        title: "",
        parcelDescription: "",
        regionName: "Tula",
      }),
    ).toBe("Tula");
  });

  it("trims description whitespace", () => {
    expect(
      resolveListingHeadline({
        title: null,
        parcelDescription: "   ",
        regionName: "Tula",
      }),
    ).toBe("Tula");
    expect(
      resolveListingHeadline({
        title: null,
        parcelDescription: "  Lot  ",
        regionName: "Tula",
      }),
    ).toBe("Lot");
  });
});
