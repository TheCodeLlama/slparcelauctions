import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { ParcelLayoutMapPlaceholder } from "./ParcelLayoutMapPlaceholder";

describe("ParcelLayoutMapPlaceholder", () => {
  it("renders the coming-soon copy", () => {
    renderWithProviders(<ParcelLayoutMapPlaceholder />);
    expect(screen.getByText("Parcel map coming soon")).toBeInTheDocument();
  });

  it("is hidden on mobile via the `hidden md:block` class toggle", () => {
    renderWithProviders(<ParcelLayoutMapPlaceholder />);
    const el = screen.getByTestId("parcel-layout-map-placeholder");
    expect(el.className).toContain("hidden");
    expect(el.className).toContain("md:block");
  });

  it("renders under the dark theme without crashing", () => {
    renderWithProviders(<ParcelLayoutMapPlaceholder />, {
      theme: "dark",
      forceTheme: true,
    });
    expect(
      screen.getByTestId("parcel-layout-map-placeholder"),
    ).toBeInTheDocument();
  });
});
