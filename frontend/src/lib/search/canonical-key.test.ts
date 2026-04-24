import { describe, it, expect } from "vitest";
import { canonicalKey } from "./canonical-key";

describe("canonicalKey", () => {
  it("produces stable keys for same filters in different orders", () => {
    const a = canonicalKey({ region: "Tula", maturity: ["MODERATE", "GENERAL"] });
    const b = canonicalKey({ maturity: ["GENERAL", "MODERATE"], region: "Tula" });
    expect(a).toBe(b);
  });

  it("differs when values differ", () => {
    expect(canonicalKey({ region: "Tula" })).not.toBe(
      canonicalKey({ region: "Beta" }),
    );
  });

  it("omits undefined fields", () => {
    expect(canonicalKey({ region: "Tula", minPrice: undefined })).toBe(
      canonicalKey({ region: "Tula" }),
    );
  });

  it("treats arrays as order-insensitive", () => {
    const a = canonicalKey({ tags: ["A", "B", "C"] });
    const b = canonicalKey({ tags: ["C", "A", "B"] });
    expect(a).toBe(b);
  });
});
