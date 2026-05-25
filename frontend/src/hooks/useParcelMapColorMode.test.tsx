import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useParcelMapColorMode } from "./useParcelMapColorMode";

const STORAGE_KEY = "slpa:parcel-map:3d-color";

describe("useParcelMapColorMode", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("returns 'elevation' when localStorage is empty", () => {
    const { result } = renderHook(() => useParcelMapColorMode());
    expect(result.current[0]).toBe("elevation");
  });

  it("returns 'slope' when localStorage already holds 'slope'", () => {
    window.localStorage.setItem(STORAGE_KEY, "slope");
    const { result } = renderHook(() => useParcelMapColorMode());
    expect(result.current[0]).toBe("slope");
  });

  it("ignores junk values and falls back to 'elevation'", () => {
    window.localStorage.setItem(STORAGE_KEY, "asdf");
    const { result } = renderHook(() => useParcelMapColorMode());
    expect(result.current[0]).toBe("elevation");
  });

  it("setMode writes to localStorage and updates returned state", () => {
    const { result } = renderHook(() => useParcelMapColorMode());
    act(() => result.current[1]("slope"));
    expect(result.current[0]).toBe("slope");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("slope");
  });

  it("setMode back to 'elevation' updates both state and storage", () => {
    window.localStorage.setItem(STORAGE_KEY, "slope");
    const { result } = renderHook(() => useParcelMapColorMode());
    act(() => result.current[1]("elevation"));
    expect(result.current[0]).toBe("elevation");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("elevation");
  });
});
