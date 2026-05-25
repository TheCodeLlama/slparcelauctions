import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ParcelMap3DLegend } from "./ParcelMap3DLegend";

describe("ParcelMap3DLegend", () => {
  it("renders elevation labels in elevation mode", () => {
    render(<ParcelMap3DLegend mode="elevation" maxDelta={27} />);
    expect(screen.getByText("0 m")).toBeInTheDocument();
    expect(screen.getByText("+27 m")).toBeInTheDocument();
  });

  it("rounds the maxDelta label to the nearest integer meter", () => {
    render(<ParcelMap3DLegend mode="elevation" maxDelta={27.6} />);
    expect(screen.getByText("+28 m")).toBeInTheDocument();
  });

  it("renders slope labels in slope mode (maxDelta ignored)", () => {
    render(<ParcelMap3DLegend mode="slope" maxDelta={0} />);
    expect(screen.getByText("0°")).toBeInTheDocument();
    expect(screen.getByText("45°")).toBeInTheDocument();
  });

  it("renders an accessible label naming the legend", () => {
    render(<ParcelMap3DLegend mode="elevation" maxDelta={10} />);
    expect(screen.getByRole("group", { name: /3D map color scale/i })).toBeInTheDocument();
  });
});
