import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useParcelMap2DColorMode } from "./useParcelMap2DColorMode";

const STORAGE_KEY = "slpa:parcel-map:2d-color";

describe("useParcelMap2DColorMode", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("returns 'elevation' when localStorage is empty", () => {
    const { result } = renderHook(() => useParcelMap2DColorMode());
    expect(result.current[0]).toBe("elevation");
  });

  it("returns 'landuse' when localStorage already holds 'landuse'", () => {
    window.localStorage.setItem(STORAGE_KEY, "landuse");
    const { result } = renderHook(() => useParcelMap2DColorMode());
    expect(result.current[0]).toBe("landuse");
  });

  it("ignores junk values and falls back to 'elevation'", () => {
    window.localStorage.setItem(STORAGE_KEY, "asdf");
    const { result } = renderHook(() => useParcelMap2DColorMode());
    expect(result.current[0]).toBe("elevation");
  });

  it("setMode writes to localStorage and updates returned state", () => {
    const { result } = renderHook(() => useParcelMap2DColorMode());
    act(() => result.current[1]("landuse"));
    expect(result.current[0]).toBe("landuse");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("landuse");
  });

  it("setMode back to 'elevation' updates both state and storage", () => {
    window.localStorage.setItem(STORAGE_KEY, "landuse");
    const { result } = renderHook(() => useParcelMap2DColorMode());
    act(() => result.current[1]("elevation"));
    expect(result.current[0]).toBe("elevation");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("elevation");
  });
});
