import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ThemeProvider } from "next-themes";
import type { ReactNode } from "react";
import { ThemedImage } from "./ThemedImage";

// See useThemedImage.test.tsx for why we use defaultTheme + enableSystem=false
// instead of forcedTheme to drive `resolvedTheme` inside the tests.
function wrap(theme: "light" | "dark", children: ReactNode) {
  return (
    <ThemeProvider attribute="data-theme" defaultTheme={theme} enableSystem={false}>
      {children}
    </ThemeProvider>
  );
}

describe("ThemedImage", () => {
  it("renders nothing when both URLs are null", () => {
    const { container } = render(wrap("light", <ThemedImage lightSrc={null} darkSrc={null} alt="x" />));
    expect(container.querySelector("img")).toBeNull();
  });

  it("renders the light variant in light theme when both are present", () => {
    render(wrap("light", <ThemedImage lightSrc="/api/v1/light.webp" darkSrc="/api/v1/dark.webp" alt="logo" />));
    const img = screen.getByAltText("logo") as HTMLImageElement;
    expect(img.src).toContain("/api/v1/light.webp");
  });

  it("renders the dark variant in dark theme when both are present", () => {
    render(wrap("dark", <ThemedImage lightSrc="/api/v1/light.webp" darkSrc="/api/v1/dark.webp" alt="logo" />));
    const img = screen.getByAltText("logo") as HTMLImageElement;
    expect(img.src).toContain("/api/v1/dark.webp");
  });

  it("falls back to light in dark theme when dark is null", () => {
    render(wrap("dark", <ThemedImage lightSrc="/api/v1/light.webp" darkSrc={null} alt="logo" />));
    const img = screen.getByAltText("logo") as HTMLImageElement;
    expect(img.src).toContain("/api/v1/light.webp");
  });

  it("passes through className and other img props", () => {
    render(wrap("light", <ThemedImage lightSrc="/api/v1/light.webp" darkSrc={null} alt="x" className="rounded h-10" />));
    const img = screen.getByAltText("x");
    expect(img).toHaveClass("rounded", "h-10");
  });
});
