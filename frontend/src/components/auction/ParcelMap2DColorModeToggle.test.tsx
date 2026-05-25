import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { ParcelMap2DColorModeToggle } from "./ParcelMap2DColorModeToggle";

describe("ParcelMap2DColorModeToggle", () => {
  it("renders an ARIA radio group with the correct aria-label", () => {
    render(<ParcelMap2DColorModeToggle mode="elevation" onChange={() => {}} />);
    expect(screen.getByRole("radiogroup", { name: "Color by" })).toBeInTheDocument();
  });

  it("renders two radio buttons labelled Elevation and Land Use", () => {
    render(<ParcelMap2DColorModeToggle mode="elevation" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Land Use" })).toBeInTheDocument();
  });

  it("aria-checked reflects the current mode prop", () => {
    const { rerender } = render(
      <ParcelMap2DColorModeToggle mode="elevation" onChange={() => {}} />,
    );
    expect(screen.getByRole("radio", { name: "Elevation" }))
      .toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "Land Use" }))
      .toHaveAttribute("aria-checked", "false");
    rerender(<ParcelMap2DColorModeToggle mode="landuse" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" }))
      .toHaveAttribute("aria-checked", "false");
    expect(screen.getByRole("radio", { name: "Land Use" }))
      .toHaveAttribute("aria-checked", "true");
  });

  it("clicking a radio fires onChange with the corresponding mode value", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap2DColorModeToggle mode="elevation" onChange={onChange} />);
    await user.click(screen.getByRole("radio", { name: "Land Use" }));
    expect(onChange).toHaveBeenCalledWith("landuse");
  });

  it("Arrow-Right cycles selection and focus", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap2DColorModeToggle mode="elevation" onChange={onChange} />);
    screen.getByRole("radio", { name: "Elevation" }).focus();
    await user.keyboard("{ArrowRight}");
    expect(onChange).toHaveBeenCalledWith("landuse");
    expect(screen.getByRole("radio", { name: "Land Use" })).toHaveFocus();
  });

  it("Arrow-Left from Land Use wraps back to Elevation", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap2DColorModeToggle mode="landuse" onChange={onChange} />);
    screen.getByRole("radio", { name: "Land Use" }).focus();
    await user.keyboard("{ArrowLeft}");
    expect(onChange).toHaveBeenCalledWith("elevation");
    expect(screen.getByRole("radio", { name: "Elevation" })).toHaveFocus();
  });

  it("roving tabindex: active=0, inactive=-1", () => {
    render(<ParcelMap2DColorModeToggle mode="elevation" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" }))
      .toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("radio", { name: "Land Use" }))
      .toHaveAttribute("tabindex", "-1");
  });

  it("renders Land Use as aria-disabled when landUseAvailable is false", () => {
    render(
      <ParcelMap2DColorModeToggle
        mode="elevation"
        onChange={() => {}}
        landUseAvailable={false}
      />,
    );
    expect(screen.getByRole("radio", { name: "Land Use" }))
      .toHaveAttribute("aria-disabled", "true");
  });

  it("does not call onChange when the disabled Land Use option is clicked", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(
      <ParcelMap2DColorModeToggle
        mode="elevation"
        onChange={onChange}
        landUseAvailable={false}
      />,
    );
    await user.click(screen.getByRole("radio", { name: "Land Use" }));
    expect(onChange).not.toHaveBeenCalled();
  });
});
