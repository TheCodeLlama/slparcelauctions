import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { FilterSidebar } from "./FilterSidebar";

describe("FilterSidebar", () => {
  it("renders children inside an aria-labelled aside", () => {
    renderWithProviders(
      <FilterSidebar>
        <p>child content</p>
      </FilterSidebar>,
    );
    const aside = screen.getByRole("complementary", { name: /filters/i });
    expect(aside).toBeInTheDocument();
    expect(screen.getByText(/child content/i)).toBeInTheDocument();
  });

  it("merges the caller's className", () => {
    renderWithProviders(
      <FilterSidebar className="hidden md:flex">
        <span>x</span>
      </FilterSidebar>,
    );
    expect(
      screen.getByRole("complementary", { name: /filters/i }),
    ).toHaveClass("hidden");
  });

  it("renders in dark mode", () => {
    renderWithProviders(
      <FilterSidebar>
        <span>x</span>
      </FilterSidebar>,
      { theme: "dark", forceTheme: true },
    );
    expect(
      screen.getByRole("complementary", { name: /filters/i }),
    ).toBeInTheDocument();
  });
});
