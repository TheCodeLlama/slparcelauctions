import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { ParcelMap3DColorModeToggle } from "./ParcelMap3DColorModeToggle";

describe("ParcelMap3DColorModeToggle", () => {
  it("renders an ARIA radio group with the correct aria-label", () => {
    render(<ParcelMap3DColorModeToggle mode="elevation" onChange={() => {}} />);
    const group = screen.getByRole("radiogroup", { name: "Color by" });
    expect(group).toBeInTheDocument();
  });

  it("renders two radio buttons labelled Elevation and Slope", () => {
    render(<ParcelMap3DColorModeToggle mode="elevation" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Slope" })).toBeInTheDocument();
  });

  it("aria-checked reflects the current mode prop", () => {
    const { rerender } = render(
      <ParcelMap3DColorModeToggle mode="elevation" onChange={() => {}} />,
    );
    expect(screen.getByRole("radio", { name: "Elevation" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "Slope" })).toHaveAttribute("aria-checked", "false");
    rerender(<ParcelMap3DColorModeToggle mode="slope" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" })).toHaveAttribute("aria-checked", "false");
    expect(screen.getByRole("radio", { name: "Slope" })).toHaveAttribute("aria-checked", "true");
  });

  it("clicking a radio fires onChange with the corresponding mode value", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap3DColorModeToggle mode="elevation" onChange={onChange} />);
    await user.click(screen.getByRole("radio", { name: "Slope" }));
    expect(onChange).toHaveBeenCalledWith("slope");
  });

  it("Arrow-Right from Elevation moves focus and selection to Slope", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap3DColorModeToggle mode="elevation" onChange={onChange} />);
    const elev = screen.getByRole("radio", { name: "Elevation" });
    elev.focus();
    await user.keyboard("{ArrowRight}");
    expect(onChange).toHaveBeenCalledWith("slope");
  });

  it("Arrow-Left from Slope wraps focus + selection back to Elevation", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap3DColorModeToggle mode="slope" onChange={onChange} />);
    const slope = screen.getByRole("radio", { name: "Slope" });
    slope.focus();
    await user.keyboard("{ArrowLeft}");
    expect(onChange).toHaveBeenCalledWith("elevation");
  });

  it("the active radio has tabIndex 0 and the inactive radio has tabIndex -1 (roving)", () => {
    render(<ParcelMap3DColorModeToggle mode="elevation" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("radio", { name: "Slope" })).toHaveAttribute("tabindex", "-1");
  });
});
