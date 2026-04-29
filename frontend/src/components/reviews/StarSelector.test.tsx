import { describe, expect, it, vi } from "vitest";
import { useState } from "react";
import {
  fireEvent,
  renderWithProviders,
  screen,
  userEvent,
} from "@/test/render";
import { StarSelector } from "./StarSelector";

function Controlled({
  initial = null,
  onChange,
}: {
  initial?: number | null;
  onChange?: (v: number) => void;
}) {
  const [value, setValue] = useState<number | null>(initial);
  return (
    <StarSelector
      value={value}
      onChange={(v) => {
        setValue(v);
        onChange?.(v);
      }}
    />
  );
}

describe("StarSelector", () => {
  it("renders 5 stars wrapped in role=radiogroup", () => {
    renderWithProviders(<StarSelector value={null} onChange={() => {}} />);
    expect(screen.getByRole("radiogroup")).toBeInTheDocument();
    expect(screen.getAllByRole("radio")).toHaveLength(5);
  });

  it("marks the selected star with aria-checked=true", () => {
    renderWithProviders(<StarSelector value={4} onChange={() => {}} />);
    const radios = screen.getAllByRole("radio");
    expect(radios[3]).toHaveAttribute("aria-checked", "true");
    expect(radios[0]).toHaveAttribute("aria-checked", "false");
  });

  it("fires onChange with the clicked value", async () => {
    const onChange = vi.fn();
    renderWithProviders(<Controlled onChange={onChange} />);
    const user = userEvent.setup();
    await user.click(screen.getByLabelText("3 stars"));
    expect(onChange).toHaveBeenCalledWith(3);
  });

  it("fills stars up to the hovered index without changing value", async () => {
    const onChange = vi.fn();
    renderWithProviders(<StarSelector value={null} onChange={onChange} />);
    const star3 = screen.getByTestId("star-selector-3");
    fireEvent.mouseEnter(star3);
    expect(screen.getByTestId("star-selector-1")).toHaveAttribute(
      "data-filled",
      "true",
    );
    expect(screen.getByTestId("star-selector-3")).toHaveAttribute(
      "data-filled",
      "true",
    );
    expect(screen.getByTestId("star-selector-4")).toHaveAttribute(
      "data-filled",
      "false",
    );
    // No click, so onChange must not fire.
    expect(onChange).not.toHaveBeenCalled();
  });

  it("keeps the selected fill after mouse leave", () => {
    renderWithProviders(<StarSelector value={2} onChange={() => {}} />);
    const group = screen.getByRole("radiogroup");
    // Hover 5 then leave — fill must collapse back to the selected value (2).
    fireEvent.mouseEnter(screen.getByTestId("star-selector-5"));
    fireEvent.mouseLeave(group);
    expect(screen.getByTestId("star-selector-2")).toHaveAttribute(
      "data-filled",
      "true",
    );
    expect(screen.getByTestId("star-selector-3")).toHaveAttribute(
      "data-filled",
      "false",
    );
  });

  it("increments on ArrowRight and decrements on ArrowLeft with wraparound", () => {
    const onChange = vi.fn();
    const { rerender } = renderWithProviders(
      <StarSelector value={3} onChange={onChange} />,
    );
    const star3 = screen.getByTestId("star-selector-3");
    fireEvent.keyDown(star3, { key: "ArrowRight" });
    expect(onChange).toHaveBeenLastCalledWith(4);

    rerender(<StarSelector value={5} onChange={onChange} />);
    fireEvent.keyDown(screen.getByTestId("star-selector-5"), {
      key: "ArrowRight",
    });
    expect(onChange).toHaveBeenLastCalledWith(1);

    rerender(<StarSelector value={1} onChange={onChange} />);
    fireEvent.keyDown(screen.getByTestId("star-selector-1"), {
      key: "ArrowLeft",
    });
    expect(onChange).toHaveBeenLastCalledWith(5);
  });

  it("jumps to 1 on Home and 5 on End", () => {
    const onChange = vi.fn();
    renderWithProviders(<StarSelector value={3} onChange={onChange} />);
    const star3 = screen.getByTestId("star-selector-3");
    fireEvent.keyDown(star3, { key: "Home" });
    expect(onChange).toHaveBeenLastCalledWith(1);
    fireEvent.keyDown(star3, { key: "End" });
    expect(onChange).toHaveBeenLastCalledWith(5);
  });

  it("jumps directly on number keys 1-5", () => {
    const onChange = vi.fn();
    renderWithProviders(<StarSelector value={3} onChange={onChange} />);
    const star3 = screen.getByTestId("star-selector-3");
    fireEvent.keyDown(star3, { key: "4" });
    expect(onChange).toHaveBeenLastCalledWith(4);
    fireEvent.keyDown(star3, { key: "2" });
    expect(onChange).toHaveBeenLastCalledWith(2);
  });

  it("ignores keys when disabled", () => {
    const onChange = vi.fn();
    renderWithProviders(
      <StarSelector value={3} onChange={onChange} disabled />,
    );
    const star3 = screen.getByTestId("star-selector-3");
    fireEvent.keyDown(star3, { key: "ArrowRight" });
    fireEvent.click(star3);
    expect(onChange).not.toHaveBeenCalled();
  });

  it("keeps exactly one star in the tab sequence (the selected one)", () => {
    renderWithProviders(<StarSelector value={2} onChange={() => {}} />);
    const radios = screen.getAllByRole("radio");
    const tabStops = radios.filter(
      (el) => (el as HTMLButtonElement).tabIndex === 0,
    );
    expect(tabStops).toHaveLength(1);
    expect(tabStops[0]).toHaveAttribute("aria-label", "2 stars");
  });

  it("makes the first star the tab stop when nothing is selected", () => {
    renderWithProviders(<StarSelector value={null} onChange={() => {}} />);
    const radios = screen.getAllByRole("radio");
    expect((radios[0] as HTMLButtonElement).tabIndex).toBe(0);
    expect((radios[1] as HTMLButtonElement).tabIndex).toBe(-1);
  });

  it("renders in dark mode using design-system token classes", () => {
    const { container } = renderWithProviders(
      <StarSelector value={3} onChange={() => {}} />,
      { theme: "dark", forceTheme: true },
    );
    expect(container.innerHTML).not.toMatch(/#[0-9a-fA-F]{3,6}/);
  });
});
