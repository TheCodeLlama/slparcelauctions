import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useParcelMapView } from "./useParcelMapView";

const STORAGE_KEY = "slpa:parcel-map:view";

describe("useParcelMapView", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("returns '2d' when localStorage is empty", () => {
    const { result } = renderHook(() => useParcelMapView());
    expect(result.current[0]).toBe("2d");
  });

  it("returns '3d' when localStorage already holds '3d'", () => {
    window.localStorage.setItem(STORAGE_KEY, "3d");
    const { result } = renderHook(() => useParcelMapView());
    expect(result.current[0]).toBe("3d");
  });

  it("ignores junk values and falls back to '2d'", () => {
    window.localStorage.setItem(STORAGE_KEY, "asdf");
    const { result } = renderHook(() => useParcelMapView());
    expect(result.current[0]).toBe("2d");
  });

  it("setView writes to localStorage and updates returned state", () => {
    const { result } = renderHook(() => useParcelMapView());
    act(() => result.current[1]("3d"));
    expect(result.current[0]).toBe("3d");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("3d");
  });

  it("setView back to '2d' updates both state and storage", () => {
    window.localStorage.setItem(STORAGE_KEY, "3d");
    const { result } = renderHook(() => useParcelMapView());
    act(() => result.current[1]("2d"));
    expect(result.current[0]).toBe("2d");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("2d");
  });
});
