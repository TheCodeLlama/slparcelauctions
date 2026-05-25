import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ParcelMapLandUseLegend } from "./ParcelMapLandUseLegend";

describe("ParcelMapLandUseLegend", () => {
  it("renders four category swatches with labels", () => {
    render(<ParcelMapLandUseLegend />);
    expect(screen.getByText("Listed")).toBeInTheDocument();
    expect(screen.getByText("Abandoned")).toBeInTheDocument();
    expect(screen.getByText("For Sale")).toBeInTheDocument();
    expect(screen.getByText("Protected")).toBeInTheDocument();
  });

  it("renders an accessible group label", () => {
    render(<ParcelMapLandUseLegend />);
    expect(screen.getByRole("group", { name: /Land Use legend/i })).toBeInTheDocument();
  });
});
