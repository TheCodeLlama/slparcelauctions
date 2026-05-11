import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { RealtyGroupDissolvedView } from "./RealtyGroupDissolvedView";

describe("RealtyGroupDissolvedView", () => {
  it("renders the generic headline when name is missing", () => {
    renderWithProviders(<RealtyGroupDissolvedView />);
    expect(
      screen.getByText("This realty group has been dissolved"),
    ).toBeInTheDocument();
  });

  it("renders the group's name in the headline when present", () => {
    renderWithProviders(<RealtyGroupDissolvedView name="Mainland Realty" />);
    expect(
      screen.getByText("Mainland Realty has been dissolved"),
    ).toBeInTheDocument();
  });

  it("renders the formatted dissolution date when present", () => {
    renderWithProviders(
      <RealtyGroupDissolvedView
        name="Mainland Realty"
        dissolvedAt="2026-04-15T10:00:00Z"
      />,
    );
    expect(screen.getByText(/Dissolved on/)).toBeInTheDocument();
    expect(screen.getByText(/April 15, 2026/)).toBeInTheDocument();
  });

  it("renders a neutral message when dissolvedAt is missing", () => {
    renderWithProviders(<RealtyGroupDissolvedView name="X" />);
    expect(
      screen.getByText("The group is no longer active."),
    ).toBeInTheDocument();
  });
});
