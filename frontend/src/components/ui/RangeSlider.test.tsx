import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, fireEvent } from "@/test/render";
import { RangeSlider } from "./RangeSlider";

describe("RangeSlider", () => {
  it("renders two handles with values", () => {
    renderWithProviders(
      <RangeSlider min={0} max={100} value={[10, 80]} onChange={() => {}} />,
    );
    const [lo, hi] = screen.getAllByRole("slider");
    expect(lo).toHaveAttribute("aria-valuenow", "10");
    expect(hi).toHaveAttribute("aria-valuenow", "80");
  });

  it("emits new tuple on change", () => {
    const onChange = vi.fn();
    renderWithProviders(
      <RangeSlider min={0} max={100} value={[10, 80]} onChange={onChange} />,
    );
    const [lo] = screen.getAllByRole("slider");
    fireEvent.change(lo, { target: { value: "30" } });
    expect(onChange).toHaveBeenCalledWith([30, 80]);
  });

  it("clamps low handle above high", () => {
    const onChange = vi.fn();
    renderWithProviders(
      <RangeSlider min={0} max={100} value={[10, 50]} onChange={onChange} />,
    );
    const [lo] = screen.getAllByRole("slider");
    fireEvent.change(lo, { target: { value: "70" } });
    expect(onChange).toHaveBeenCalledWith([50, 50]);
  });

  it("clamps high handle below low", () => {
    const onChange = vi.fn();
    renderWithProviders(
      <RangeSlider min={0} max={100} value={[50, 80]} onChange={onChange} />,
    );
    const [, hi] = screen.getAllByRole("slider");
    fireEvent.change(hi, { target: { value: "20" } });
    expect(onChange).toHaveBeenCalledWith([50, 50]);
  });

  it("accepts custom aria labels", () => {
    renderWithProviders(
      <RangeSlider
        min={0}
        max={100}
        value={[0, 100]}
        onChange={() => {}}
        ariaLabel={["Min L$", "Max L$"]}
      />,
    );
    expect(screen.getByLabelText("Min L$")).toBeInTheDocument();
    expect(screen.getByLabelText("Max L$")).toBeInTheDocument();
  });

  it("applies the pointer-events layering class to both inputs", () => {
    // Guards the globals.css hit-region fix: without the class, the top
    // input absorbs every pointer event and the low handle is mouse-inert.
    // Hit-region correctness itself is visual-only in jsdom — the manual
    // test is documented on the component's JSDoc.
    renderWithProviders(
      <RangeSlider min={0} max={100} value={[10, 80]} onChange={() => {}} />,
    );
    const [lo, hi] = screen.getAllByRole("slider");
    expect(lo).toHaveClass("range-slider-input");
    expect(hi).toHaveClass("range-slider-input");
  });
});
