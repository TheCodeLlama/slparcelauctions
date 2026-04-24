import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useMediaQuery } from "./useMediaQuery";

type Listener = () => void;

describe("useMediaQuery", () => {
  let listeners: Listener[];
  let matches: boolean;

  beforeEach(() => {
    listeners = [];
    matches = false;
    // Override the vitest.setup.ts stub so we can toggle matches at runtime
    // and drive listeners directly.
    Object.defineProperty(window, "matchMedia", {
      writable: true,
      configurable: true,
      value: vi.fn((query: string) => ({
        get matches() {
          return matches;
        },
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: (_: string, cb: Listener) => {
          listeners.push(cb);
        },
        removeEventListener: (_: string, cb: Listener) => {
          listeners = listeners.filter((l) => l !== cb);
        },
        dispatchEvent: vi.fn(),
      })),
    });
  });

  afterEach(() => {
    listeners = [];
  });

  it("returns the initial match value from matchMedia", () => {
    matches = true;
    const { result } = renderHook(() => useMediaQuery("(min-width: 768px)"));
    expect(result.current).toBe(true);
  });

  it("re-renders when the media query listener fires", () => {
    matches = false;
    const { result } = renderHook(() => useMediaQuery("(min-width: 768px)"));
    expect(result.current).toBe(false);
    act(() => {
      matches = true;
      for (const l of listeners) l();
    });
    expect(result.current).toBe(true);
  });
});
