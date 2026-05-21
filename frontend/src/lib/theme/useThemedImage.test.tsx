import { describe, it, expect } from "vitest";
import { renderHook } from "@testing-library/react";
import { ThemeProvider } from "next-themes";
import type { ReactNode } from "react";
import { useThemedImage } from "./useThemedImage";

// next-themes computes `resolvedTheme` from the internal `theme` state (which
// is seeded from localStorage or `defaultTheme`), NOT from `forcedTheme` — the
// `forcedTheme` prop only drives the DOM side-effect. So to make `useTheme()`
// return a specific `resolvedTheme` in tests, we set `defaultTheme={theme}`
// with `enableSystem={false}`. This mirrors the pattern in `src/test/render.tsx`.
function wrapper(theme: "light" | "dark") {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <ThemeProvider
        attribute="data-theme"
        defaultTheme={theme}
        enableSystem={false}
      >
        {children}
      </ThemeProvider>
    );
  }
  return Wrapper;
}

describe("useThemedImage", () => {
  it("returns dark when both present in dark theme", () => {
    const { result } = renderHook(() => useThemedImage("L", "D"), { wrapper: wrapper("dark") });
    expect(result.current).toBe("D");
  });
  it("returns light when both present in light theme", () => {
    const { result } = renderHook(() => useThemedImage("L", "D"), { wrapper: wrapper("light") });
    expect(result.current).toBe("L");
  });
  it("falls back to light in dark theme when dark is null", () => {
    const { result } = renderHook(() => useThemedImage("L", null), { wrapper: wrapper("dark") });
    expect(result.current).toBe("L");
  });
  it("falls back to dark in light theme when light is null", () => {
    const { result } = renderHook(() => useThemedImage(null, "D"), { wrapper: wrapper("light") });
    expect(result.current).toBe("D");
  });
  it("returns null when both null", () => {
    const { result } = renderHook(() => useThemedImage(null, null), { wrapper: wrapper("light") });
    expect(result.current).toBeNull();
  });
});
